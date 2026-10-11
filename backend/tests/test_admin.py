import base64
import os
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path
from threading import Lock
from unittest.mock import patch
from uuid import UUID, uuid4

import rfc8785
from alembic import command
from alembic.config import Config
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, func, select, text
from sqlalchemy.orm import Session

from app.admin import admin_settings
from app.authority import FabricClient, FabricUnavailable, action_document
from app.bootstrap import bootstrap_admin
from app.config import Settings
from app.database import get_session
from app.main import app
from app.models import (
    Administrator, Appointment, AuthorityKey, Club, Competition, Credential,
    FabricTransaction, ResultRecipient, ResultSubmission, User,
)
from app.permissions import active_appointments, permission_settings
from app.session import SESSION_COOKIE, create_session


@unittest.skipUnless(os.getenv("INSTICHAIN_TEST_DATABASE_URL"), "Set INSTICHAIN_TEST_DATABASE_URL")
class AdminTests(unittest.TestCase):
    def setUp(self):
        self.schema = "test_admin_" + uuid4().hex
        self.root = create_engine(os.environ["INSTICHAIN_TEST_DATABASE_URL"])
        self.addCleanup(self.root.dispose)
        with self.root.begin() as connection:
            connection.execute(text(f'CREATE SCHEMA "{self.schema}"'))
        self.addCleanup(self.drop_schema)
        self.engine = create_engine(os.environ["INSTICHAIN_TEST_DATABASE_URL"],
                                    connect_args={"options": f"-csearch_path={self.schema}"})
        self.addCleanup(self.engine.dispose)
        config = Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))
        with self.engine.begin() as connection:
            config.attributes["connection"] = connection
            command.upgrade(config, "head")
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.key_path = Path(directory.name) / "admin.pem"
        self.private_key = Ed25519PrivateKey.generate()
        self.key_path.write_bytes(self.private_key.private_bytes(serialization.Encoding.PEM,
                                  serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        self.key_path.chmod(0o600)
        if os.name == "nt":
            # Windows reports POSIX-irrelevant mode bits, so the owner-only check
            # in load_signing_key cannot pass here. The check itself is tested on
            # its own in test_preflight.
            permissive = patch("app.authority.Path.stat", autospec=True,
                               side_effect=lambda self, **kwargs: os.stat_result(
                                   (0o100600,) + tuple(os.stat(self))[1:]))
            permissive.start()
            self.addCleanup(permissive.stop)
        self.settings = Settings(_env_file=None, admin_signing_key_file=self.key_path,
                                 fabric_bridge_token="test-bridge-token")
        self.fabric_sequence = 0
        self.fabric_lock = Lock()

        def submit(_client, document):
            with self.fabric_lock:
                self.fabric_sequence += 1
                sequence = self.fabric_sequence
            return document | {
                "schema_version": 1,
                "sequence": sequence,
                "fabric_transaction_id": f"fabric-{document['request_id']}",
                "submitted_transaction_id": f"submitted-{sequence}",
                "block_number": str(sequence),
                "validation_code": 0,
                "committed_at": self.now.isoformat(),
            }

        fabric_patch = patch.object(FabricClient, "submit", autospec=True, side_effect=submit)
        fabric_patch.start()
        self.addCleanup(fabric_patch.stop)
        self.now = datetime.now(timezone.utc)
        self.users = {}
        with Session(self.engine) as db, db.begin():
            for name in ["admin", "advisor", "student", "unverified"]:
                user = User(id=uuid4(), email=f"{name}@iitm.ac.in", display_name=name,
                            email_verified_at=None if name == "unverified" else self.now)
                db.add(user)
                self.users[name] = user.id

        def sessions():
            with Session(self.engine) as db:
                yield db

        app.dependency_overrides[get_session] = sessions
        app.dependency_overrides[admin_settings] = lambda: self.settings
        app.dependency_overrides[permission_settings] = lambda: self.settings
        self.addCleanup(app.dependency_overrides.clear)
        self.client = TestClient(app)
        self.addCleanup(self.client.close)
        self.client.headers["Origin"] = "http://127.0.0.1:5173"
        self.login("admin")

    def drop_schema(self):
        with self.root.begin() as connection:
            connection.execute(text(f'DROP SCHEMA "{self.schema}" CASCADE'))

    def login(self, name):
        with Session(self.engine) as db, db.begin():
            token = create_session(db, self.users[name], self.settings)
        self.client.cookies.set(SESSION_COOKIE, token)

    def bootstrap(self, name="admin"):
        with Session(self.engine) as db, db.begin():
            return bootstrap_admin(db, f"{name}@iitm.ac.in", "Institutional approval 001",
                                   self.private_key, self.settings).user_id

    def club(self, slug="coding"):
        response = self.client.post("/api/admin/clubs", json={"name": slug.title(), "slug": slug})
        self.assertEqual(response.status_code, 201, response.text)
        return response.json()["id"]

    def advisor_body(self, **overrides):
        self.advisor_private_key = Ed25519PrivateKey.generate()
        return dict(email="advisor@iitm.ac.in", public_key=base64.b64encode(
            self.advisor_private_key.public_key().public_bytes_raw()).decode(),
            verification_reference="Faculty directory confirmation 002",
            starts_at=(self.now - timedelta(minutes=1)).isoformat(),
            ends_at=(self.now + timedelta(days=365)).isoformat()) | overrides

    def appoint_advisor(self, club, **overrides):
        response = self.client.post(
            f"/api/admin/clubs/{club}/advisors",
            json=self.advisor_body(**overrides),
        )
        self.assertEqual(response.status_code, 201, response.text)
        return response.json()["id"]

    def prepare_role(self, club, **overrides):
        body = dict(
            email="student@iitm.ac.in",
            position="club_head",
            starts_at=(self.now - timedelta(minutes=1)).isoformat(),
            ends_at=(self.now + timedelta(days=180)).isoformat(),
        ) | overrides
        response = self.client.post(f"/api/advisor/clubs/{club}/role-grants/prepare", json=body)
        self.assertEqual(response.status_code, 200, response.text)
        prepared = response.json()
        self.assertEqual(base64.b64decode(prepared["signing_payload"]), rfc8785.dumps(prepared["document"]))
        return prepared

    def submit_role(self, club, prepared, signature=None):
        signature = signature or self.advisor_private_key.sign(base64.b64decode(prepared["signing_payload"]))
        return self.client.post(f"/api/advisor/clubs/{club}/role-grants", json={
            "document": prepared["document"],
            "signature": base64.b64encode(signature).decode(),
        })

    def prepare_revocation(self, club, appointment, reason="Annual club handover"):
        response = self.client.post(
            f"/api/advisor/clubs/{club}/roles/{appointment}/revocations/prepare",
            json={"reason": reason},
        )
        self.assertEqual(response.status_code, 200, response.text)
        prepared = response.json()
        self.assertEqual(base64.b64decode(prepared["signing_payload"]), rfc8785.dumps(prepared["document"]))
        return prepared

    def submit_revocation(self, club, appointment, prepared, signature=None):
        signature = signature or self.advisor_private_key.sign(base64.b64decode(prepared["signing_payload"]))
        return self.client.post(
            f"/api/advisor/clubs/{club}/roles/{appointment}/revocations",
            json={
                "document": prepared["document"],
                "signature": base64.b64encode(signature).decode(),
            },
        )

    def competition_body(self, **overrides):
        return dict(
            title="Inter-IIT Programming Contest",
            description="Annual team programming competition",
            starts_at=(self.now + timedelta(days=30)).isoformat(),
            ends_at=(self.now + timedelta(days=31)).isoformat(),
        ) | overrides

    def create_competition(self, club, **overrides):
        response = self.client.post(
            f"/api/clubs/{club}/competitions",
            json=self.competition_body(**overrides),
        )
        self.assertEqual(response.status_code, 201, response.text)
        return response.json()

    def result_body(self, **overrides):
        return dict(
            award="First place",
            proof_digest="a" * 64,
            recipient_emails=["student@iitm.ac.in", "admin@iitm.ac.in"],
        ) | overrides

    def prepare_issuance(self, club, competition, result_id, version):
        path = (
            f"/api/clubs/{club}/competitions/{competition}/results/"
            f"{result_id}/versions/{version}/issuance"
        )
        response = self.client.post(f"{path}/prepare")
        self.assertEqual(response.status_code, 200, response.text)
        prepared = response.json()
        self.assertEqual(base64.b64decode(prepared["signing_payload"]), rfc8785.dumps(prepared["document"]))
        return path, prepared

    def submit_issuance(self, path, prepared, signature=None):
        signature = signature or self.advisor_private_key.sign(base64.b64decode(prepared["signing_payload"]))
        return self.client.post(path, json={
            "document": prepared["document"],
            "signature": base64.b64encode(signature).decode(),
        })

    def test_bootstrap_verified_only_and_idempotent(self):
        with self.assertRaises(ValueError):
            self.bootstrap("unverified")
        self.assertEqual(self.bootstrap(), self.users["admin"])
        self.assertEqual(self.bootstrap(), self.users["admin"])
        with self.assertRaises(ValueError):
            self.bootstrap("student")
        with Session(self.engine) as db:
            self.assertEqual(db.scalar(select(func.count()).select_from(Administrator)), 1)
            self.assertEqual(db.scalar(select(func.count()).select_from(FabricTransaction)), 1)

    def test_racing_bootstraps_establish_only_one_admin(self):
        def attempt(name):
            try:
                self.bootstrap(name)
                return True
            except ValueError:
                return False
        with ThreadPoolExecutor(max_workers=2) as pool:
            self.assertEqual(sorted(pool.map(attempt, ["admin", "student"])), [False, True])

    def test_permission_and_origin_checks(self):
        self.bootstrap()
        self.client.cookies.clear()
        self.assertEqual(self.client.get("/api/admin/clubs").status_code, 401)
        self.login("student")
        self.assertEqual(self.client.get("/api/admin/access").status_code, 403)
        self.assertEqual(self.client.post("/api/admin/clubs", json={"name": "X", "slug": "x"}).status_code, 403)
        self.login("admin")
        self.client.headers["Origin"] = "https://attacker.example"
        self.assertEqual(self.client.post("/api/admin/clubs", json={"name": "X", "slug": "x"}).status_code, 403)

    def test_club_creation_conflict_and_no_self_promotion(self):
        self.bootstrap()
        self.club()
        self.assertEqual(self.client.post("/api/admin/clubs", json={"name": "Duplicate", "slug": "coding"}).status_code, 409)
        self.assertEqual(self.client.post("/api/admin/clubs", json={"name": "Other", "slug": "other", "role": "admin"}).status_code, 422)
        with Session(self.engine) as db:
            self.assertEqual(db.scalar(select(func.count()).select_from(Club)), 1)
            self.assertEqual(db.scalar(select(func.count()).select_from(FabricTransaction)), 2)

    def test_advisor_binding_and_signed_chain(self):
        self.bootstrap()
        club = self.club()
        response = self.client.post(f"/api/admin/clubs/{club}/advisors", json=self.advisor_body())
        self.assertEqual(response.status_code, 201, response.text)
        with Session(self.engine) as db:
            appointment = db.scalar(select(Appointment))
            self.assertEqual(appointment.position, "faculty_advisor")
            self.assertEqual(appointment.user_id, self.users["advisor"])
            self.assertGreaterEqual(appointment.starts_at, self.now)
            self.assertEqual(db.get(AuthorityKey, appointment.advisor_key_id).user_id, appointment.user_id)
            for sequence, entry in enumerate(db.scalars(select(FabricTransaction).where(
                    FabricTransaction.source == "fabric").order_by(FabricTransaction.sequence)), 1):
                self.assertEqual(entry.sequence, sequence)
                key = db.get(AuthorityKey, entry.actor_key_id)
                document = action_document(entry.request_id, entry.event_type, entry.object_ids,
                                           entry.payload, entry.actor_id, entry.actor_key_id)
                Ed25519PublicKey.from_public_bytes(key.public_key).verify(
                    entry.signature, rfc8785.dumps(document))
        self.assertEqual(self.client.get("/api/admin/clubs").json()[0]["advisors"][0]["status"], "active")
        self.login("advisor")
        self.assertEqual(self.client.get("/api/admin/access").status_code, 403)

    def test_advisor_access_is_limited_to_assigned_clubs(self):
        self.bootstrap()
        assigned_club = self.club()
        other_club = self.club("robotics")
        appointment = self.appoint_advisor(assigned_club)
        self.login("advisor")

        self.assertEqual(self.client.get("/api/advisor/access").status_code, 200)
        response = self.client.get("/api/advisor/clubs")
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(len(response.json()), 1)
        self.assertEqual(response.json()[0]["id"], assigned_club)
        self.assertEqual(response.json()[0]["slug"], "coding")
        self.assertEqual(response.json()[0]["name"], "Coding")
        self.assertEqual(response.json()[0]["appointment_id"], appointment)
        self.assertEqual(
            self.client.get(f"/api/advisor/clubs/{assigned_club}/access").status_code,
            200,
        )
        self.assertEqual(
            self.client.get(f"/api/advisor/clubs/{other_club}/access").status_code,
            403,
        )

    def test_advisor_routes_reject_anonymous_and_ordinary_users(self):
        self.client.cookies.clear()
        self.assertEqual(self.client.get("/api/advisor/access").status_code, 401)
        self.login("student")
        self.assertEqual(self.client.get("/api/advisor/access").status_code, 403)
        self.assertEqual(self.client.get("/api/advisor/clubs").status_code, 403)

    def test_scheduled_expired_and_revoked_advisors_are_denied(self):
        self.bootstrap()
        club = self.club()
        appointment_id = self.appoint_advisor(club)
        self.login("advisor")

        with Session(self.engine) as db, db.begin():
            appointment = db.get(Appointment, appointment_id)
            appointment.starts_at = self.now + timedelta(days=1)
            appointment.ends_at = self.now + timedelta(days=2)
        self.assertEqual(self.client.get("/api/advisor/access").status_code, 403)

        with Session(self.engine) as db, db.begin():
            appointment = db.get(Appointment, appointment_id)
            appointment.starts_at = self.now - timedelta(days=2)
            appointment.ends_at = self.now - timedelta(days=1)
        self.assertEqual(self.client.get("/api/advisor/access").status_code, 403)

        with Session(self.engine) as db, db.begin():
            appointment = db.get(Appointment, appointment_id)
            appointment.starts_at = self.now - timedelta(days=1)
            appointment.ends_at = self.now + timedelta(days=1)
            appointment.revoked_at = db.scalar(select(func.clock_timestamp()))
            appointment.revocation_transaction_id = appointment.grant_transaction_id
        self.assertEqual(self.client.get("/api/advisor/access").status_code, 403)

    def test_advisor_grants_signed_club_head_role_idempotently(self):
        self.bootstrap()
        club = self.club()
        self.appoint_advisor(club)
        self.login("advisor")

        prepared = self.prepare_role(club)
        response = self.submit_role(club, prepared)
        self.assertEqual(response.status_code, 201, response.text)
        self.assertEqual(response.json()["position"], "club_head")
        self.assertEqual(response.json()["user_id"], str(self.users["student"]))
        self.assertEqual(self.submit_role(club, prepared).json(), response.json())

        with Session(self.engine) as db:
            appointment = db.get(Appointment, response.json()["id"])
            event = db.get(FabricTransaction, appointment.grant_transaction_id)
            key = db.get(AuthorityKey, appointment.advisor_key_id)
            self.assertEqual(appointment.granted_by_id, self.users["advisor"])
            self.assertEqual(event.event_type, "ROLE_GRANTED")
            Ed25519PublicKey.from_public_bytes(key.public_key).verify(
                event.signature,
                rfc8785.dumps(action_document(
                    event.request_id, event.event_type, event.object_ids,
                    event.payload, event.actor_id, event.actor_key_id,
                )),
            )

    def test_role_grants_enforce_scope_origin_recipient_and_signature(self):
        self.bootstrap()
        club = self.club()
        other_club = self.club("robotics")
        self.appoint_advisor(club)
        self.login("advisor")

        invalid_role = self.client.post(f"/api/advisor/clubs/{club}/role-grants/prepare", json={
            "email": "student@iitm.ac.in",
            "position": "faculty_advisor",
            "starts_at": self.now.isoformat(),
            "ends_at": (self.now + timedelta(days=1)).isoformat(),
        })
        self.assertEqual(invalid_role.status_code, 422)
        self.assertEqual(self.client.post(
            f"/api/advisor/clubs/{club}/role-grants/prepare",
            json={
                "email": "unverified@iitm.ac.in", "position": "club_head",
                "starts_at": self.now.isoformat(),
                "ends_at": (self.now + timedelta(days=1)).isoformat(),
            },
        ).status_code, 422)
        self.assertEqual(self.client.post(
            f"/api/advisor/clubs/{other_club}/role-grants/prepare",
            json={
                "email": "student@iitm.ac.in", "position": "club_head",
                "starts_at": self.now.isoformat(),
                "ends_at": (self.now + timedelta(days=1)).isoformat(),
            },
        ).status_code, 403)

        prepared = self.prepare_role(club)
        self.assertEqual(self.submit_role(club, prepared, bytes(64)).status_code, 422)
        self.client.headers["Origin"] = "https://attacker.example"
        self.assertEqual(self.submit_role(club, prepared).status_code, 403)

    def test_role_grant_overlap_and_fabric_failure_leave_no_partial_role(self):
        self.bootstrap()
        club = self.club()
        self.appoint_advisor(club)
        self.login("advisor")

        first = self.prepare_role(club)
        self.assertEqual(self.submit_role(club, first).status_code, 201)
        overlapping = self.prepare_role(club, email="admin@iitm.ac.in")
        self.assertEqual(self.submit_role(club, overlapping).status_code, 409)

        office_bearer = self.prepare_role(club, position="office_bearer")
        with patch.object(FabricClient, "submit", side_effect=FabricUnavailable("offline")):
            self.assertEqual(self.submit_role(club, office_bearer).status_code, 503)
        with Session(self.engine) as db:
            roles = db.scalars(select(Appointment).where(
                Appointment.position.in_(("club_head", "office_bearer")),
            )).all()
            self.assertEqual(len(roles), 1)

    def test_advisor_revokes_signed_role_idempotently(self):
        self.bootstrap()
        club = self.club()
        self.appoint_advisor(club)
        self.login("advisor")
        granted = self.submit_role(club, self.prepare_role(club)).json()

        roles = self.client.get(f"/api/advisor/clubs/{club}/roles")
        self.assertEqual(roles.status_code, 200, roles.text)
        self.assertEqual(roles.json()[0]["status"], "active")
        prepared = self.prepare_revocation(club, granted["id"])
        response = self.submit_revocation(club, granted["id"], prepared)
        self.assertEqual(response.status_code, 201, response.text)
        self.assertEqual(self.submit_revocation(club, granted["id"], prepared).json(), response.json())
        self.assertEqual(
            self.client.get(f"/api/advisor/clubs/{club}/roles").json()[0]["status"],
            "revoked",
        )

        with Session(self.engine) as db:
            appointment = db.get(Appointment, granted["id"])
            event = db.get(FabricTransaction, appointment.revocation_transaction_id)
            key = db.get(AuthorityKey, appointment.advisor_key_id)
            self.assertIsNotNone(appointment.revoked_at)
            self.assertEqual(active_appointments(
                db, self.users["student"], ("club_head",), appointment.club_id,
            ), ())
            self.assertEqual(event.event_type, "ROLE_REVOKED")
            self.assertEqual(event.payload["reason"], "Annual club handover")
            Ed25519PublicKey.from_public_bytes(key.public_key).verify(
                event.signature,
                rfc8785.dumps(action_document(
                    event.request_id, event.event_type, event.object_ids,
                    event.payload, event.actor_id, event.actor_key_id,
                )),
            )

        successor = self.submit_role(
            club,
            self.prepare_role(club, email="admin@iitm.ac.in"),
        )
        self.assertEqual(successor.status_code, 201, successor.text)

    def test_role_revocation_rejects_wrong_scope_target_and_signature(self):
        self.bootstrap()
        club = self.club()
        other_club = self.club("robotics")
        advisor_appointment = self.appoint_advisor(club)
        self.login("advisor")
        granted = self.submit_role(club, self.prepare_role(club)).json()

        self.assertEqual(self.client.post(
            f"/api/advisor/clubs/{other_club}/roles/{granted['id']}/revocations/prepare",
            json={"reason": "Wrong club"},
        ).status_code, 403)
        self.assertEqual(self.client.post(
            f"/api/advisor/clubs/{club}/roles/{advisor_appointment}/revocations/prepare",
            json={"reason": "Not permitted"},
        ).status_code, 404)
        prepared = self.prepare_revocation(club, granted["id"])
        self.assertEqual(
            self.submit_revocation(club, granted["id"], prepared, bytes(64)).status_code,
            422,
        )
        prepared["document"]["payload"]["reason"] = "Tampered reason"
        self.assertEqual(self.submit_revocation(club, granted["id"], prepared).status_code, 422)

    def test_role_revocation_rejects_expired_role_and_rolls_back_fabric_failure(self):
        self.bootstrap()
        club = self.club()
        self.appoint_advisor(club)
        self.login("advisor")
        granted = self.submit_role(club, self.prepare_role(club)).json()
        prepared = self.prepare_revocation(club, granted["id"])

        with patch.object(FabricClient, "submit", side_effect=FabricUnavailable("offline")):
            self.assertEqual(
                self.submit_revocation(club, granted["id"], prepared).status_code,
                503,
            )
        with Session(self.engine) as db:
            self.assertIsNone(db.get(Appointment, granted["id"]).revoked_at)

        with Session(self.engine) as db, db.begin():
            appointment = db.get(Appointment, granted["id"])
            appointment.starts_at = self.now - timedelta(days=2)
            appointment.ends_at = self.now - timedelta(days=1)
        response = self.client.post(
            f"/api/advisor/clubs/{club}/roles/{granted['id']}/revocations/prepare",
            json={"reason": "Too late"},
        )
        self.assertEqual(response.status_code, 409)

    def test_club_head_creates_and_advisor_views_competitions(self):
        self.bootstrap()
        club = self.club()
        self.appoint_advisor(club)
        self.login("advisor")
        role = self.submit_role(club, self.prepare_role(club)).json()
        self.login("student")

        response = self.client.post(
            f"/api/clubs/{club}/competitions",
            json=self.competition_body(),
        )
        self.assertEqual(response.status_code, 201, response.text)
        competition = response.json()
        self.assertEqual(competition["club_id"], club)
        self.assertEqual(competition["created_by_id"], str(self.users["student"]))
        self.assertEqual(competition["created_by_name"], "student")
        self.assertEqual(
            self.client.get(f"/api/clubs/{club}/competitions/{competition['id']}").json(),
            competition,
        )
        self.assertEqual(self.client.get(f"/api/clubs/{club}/competitions").json(), [competition])

        self.login("advisor")
        self.assertEqual(self.client.get(f"/api/clubs/{club}/competitions").json(), [competition])
        with Session(self.engine) as db:
            stored = db.get(Competition, competition["id"])
            self.assertEqual(stored.created_by_id, self.users["student"])
            self.assertEqual(db.scalar(select(func.count()).select_from(FabricTransaction)), 4)
            self.assertEqual(db.get(Appointment, role["id"]).position, "club_head")

    def test_office_bearer_can_create_competition(self):
        self.bootstrap()
        club = self.club()
        self.appoint_advisor(club)
        self.login("advisor")
        self.assertEqual(self.submit_role(
            club,
            self.prepare_role(club, position="office_bearer"),
        ).status_code, 201)
        self.login("student")
        self.assertEqual(self.client.post(
            f"/api/clubs/{club}/competitions",
            json=self.competition_body(title="Robotics Challenge"),
        ).status_code, 201)

    def test_competitions_enforce_role_scope_revocation_and_input_ownership(self):
        self.bootstrap()
        club = self.club()
        other_club = self.club("robotics")
        self.appoint_advisor(club)
        self.login("advisor")
        role = self.submit_role(club, self.prepare_role(club)).json()
        self.assertEqual(self.client.post(
            f"/api/clubs/{club}/competitions",
            json=self.competition_body(),
        ).status_code, 403)

        self.login("student")
        self.assertEqual(self.client.post(
            f"/api/clubs/{other_club}/competitions",
            json=self.competition_body(),
        ).status_code, 403)
        self.assertEqual(self.client.post(
            f"/api/clubs/{club}/competitions",
            json=self.competition_body(ends_at=self.now.isoformat()),
        ).status_code, 422)
        self.assertEqual(self.client.post(
            f"/api/clubs/{club}/competitions",
            json=self.competition_body(created_by_id=str(self.users["admin"])),
        ).status_code, 422)

        self.login("advisor")
        prepared = self.prepare_revocation(club, role["id"])
        self.assertEqual(self.submit_revocation(club, role["id"], prepared).status_code, 201)
        self.login("student")
        self.assertEqual(self.client.post(
            f"/api/clubs/{club}/competitions",
            json=self.competition_body(),
        ).status_code, 403)
        self.assertEqual(self.client.get(f"/api/clubs/{club}/competitions").status_code, 403)

    def test_club_head_submits_immutable_team_result_versions(self):
        self.bootstrap()
        club = self.club()
        self.appoint_advisor(club)
        self.login("advisor")
        self.assertEqual(self.submit_role(club, self.prepare_role(club)).status_code, 201)
        self.login("student")
        competition = self.create_competition(club)
        path = f"/api/clubs/{club}/competitions/{competition['id']}/results"

        first = self.client.post(path, json=self.result_body())
        self.assertEqual(first.status_code, 201, first.text)
        first = first.json()
        self.assertEqual(first["version"], 1)
        self.assertEqual(len(first["recipients"]), 2)
        second = self.client.post(
            f"{path}/{first['result_id']}/versions",
            json=self.result_body(
                award="Winners",
                proof_digest="b" * 64,
                recipient_emails=["student@iitm.ac.in"],
            ),
        )
        self.assertEqual(second.status_code, 201, second.text)
        self.assertEqual(second.json()["version"], 2)
        self.assertEqual(second.json()["result_id"], first["result_id"])
        self.assertEqual(
            self.client.get(f"{path}/{first['result_id']}/versions/1").json(),
            first,
        )
        self.assertEqual(len(self.client.get(path).json()), 2)

        self.login("advisor")
        self.assertEqual(len(self.client.get(path).json()), 2)
        with Session(self.engine) as db:
            self.assertEqual(db.scalar(select(func.count()).select_from(ResultSubmission)), 2)
            self.assertEqual(db.scalar(select(func.count()).select_from(ResultRecipient)), 3)
            self.assertEqual(db.scalar(select(func.count()).select_from(FabricTransaction)), 4)

    def test_result_submission_validates_recipients_digest_and_club_scope(self):
        self.bootstrap()
        club = self.club()
        other_club = self.club("robotics")
        self.appoint_advisor(club)
        self.login("advisor")
        self.assertEqual(self.submit_role(club, self.prepare_role(club)).status_code, 201)
        self.login("student")
        competition = self.create_competition(club)
        path = f"/api/clubs/{club}/competitions/{competition['id']}/results"

        for body in [
            self.result_body(proof_digest="invalid"),
            self.result_body(recipient_emails=[]),
            self.result_body(recipient_emails=["student@iitm.ac.in", "student@iitm.ac.in"]),
            self.result_body(recipient_emails=["unverified@iitm.ac.in"]),
            self.result_body(version=7),
        ]:
            with self.subTest(body=body):
                self.assertEqual(self.client.post(path, json=body).status_code, 422)
        self.assertEqual(self.client.post(
            f"/api/clubs/{other_club}/competitions/{competition['id']}/results",
            json=self.result_body(),
        ).status_code, 403)
        with Session(self.engine) as db:
            self.assertEqual(db.scalar(select(func.count()).select_from(ResultSubmission)), 0)

    def test_revoked_officer_cannot_submit_new_result_version(self):
        self.bootstrap()
        club = self.club()
        self.appoint_advisor(club)
        self.login("advisor")
        role = self.submit_role(club, self.prepare_role(club)).json()
        self.login("student")
        competition = self.create_competition(club)
        path = f"/api/clubs/{club}/competitions/{competition['id']}/results"
        first = self.client.post(path, json=self.result_body()).json()

        self.login("advisor")
        prepared = self.prepare_revocation(club, role["id"])
        self.assertEqual(self.submit_revocation(club, role["id"], prepared).status_code, 201)
        self.login("student")
        self.assertEqual(self.client.post(
            f"{path}/{first['result_id']}/versions",
            json=self.result_body(proof_digest="b" * 64),
        ).status_code, 403)

    def test_advisor_issues_team_credentials_on_fabric_idempotently(self):
        self.bootstrap()
        club = self.club()
        self.appoint_advisor(club)
        self.login("advisor")
        self.assertEqual(self.submit_role(club, self.prepare_role(club)).status_code, 201)
        self.login("student")
        competition = self.create_competition(club)
        result_path = f"/api/clubs/{club}/competitions/{competition['id']}/results"
        submission = self.client.post(result_path, json=self.result_body()).json()
        self.assertEqual(self.client.post(
            f"{result_path}/{submission['result_id']}/versions/1/issuance/prepare",
        ).status_code, 403)

        self.login("advisor")
        path, prepared = self.prepare_issuance(
            club, competition["id"], submission["result_id"], 1,
        )
        response = self.submit_issuance(path, prepared)
        self.assertEqual(response.status_code, 201, response.text)
        issued = response.json()
        self.assertEqual(len(issued["credentials"]), 2)
        self.assertEqual(self.submit_issuance(path, prepared).json(), issued)

        with Session(self.engine) as db:
            credentials = db.scalars(select(Credential).order_by(Credential.recipient_id)).all()
            self.assertEqual(len(credentials), 2)
            self.assertEqual(len({item.issuance_transaction_id for item in credentials}), 1)
            event = db.get(FabricTransaction, credentials[0].issuance_transaction_id)
            key = db.get(AuthorityKey, event.actor_key_id)
            self.assertEqual(event.event_type, "ACHIEVEMENT_ISSUED")
            self.assertEqual(len(event.payload["claims"]), 2)
            Ed25519PublicKey.from_public_bytes(key.public_key).verify(
                event.signature,
                rfc8785.dumps(action_document(
                    event.request_id, event.event_type, event.object_ids,
                    event.payload, event.actor_id, event.actor_key_id,
                )),
            )

    def test_credential_issuance_rejects_tampering_and_rolls_back_fabric_failure(self):
        self.bootstrap()
        club = self.club()
        other_club = self.club("robotics")
        self.appoint_advisor(club)
        self.login("advisor")
        self.assertEqual(self.submit_role(club, self.prepare_role(club)).status_code, 201)
        self.login("student")
        competition = self.create_competition(club)
        result_path = f"/api/clubs/{club}/competitions/{competition['id']}/results"
        submission = self.client.post(result_path, json=self.result_body()).json()
        self.login("advisor")
        path, prepared = self.prepare_issuance(
            club, competition["id"], submission["result_id"], 1,
        )

        self.assertEqual(self.submit_issuance(path, prepared, bytes(64)).status_code, 422)
        tampered = prepared["document"] | {
            "payload": prepared["document"]["payload"] | {"award": "Tampered"},
        }
        signature = self.advisor_private_key.sign(rfc8785.dumps(tampered))
        self.assertEqual(self.client.post(path, json={
            "document": tampered,
            "signature": base64.b64encode(signature).decode(),
        }).status_code, 422)
        self.assertEqual(self.client.post(
            path.replace(f"clubs/{club}", f"clubs/{other_club}"),
            json={
                "document": prepared["document"],
                "signature": base64.b64encode(signature).decode(),
            },
        ).status_code, 403)
        with patch.object(FabricClient, "submit", side_effect=FabricUnavailable("offline")):
            self.assertEqual(self.submit_issuance(path, prepared).status_code, 503)
        with Session(self.engine) as db:
            self.assertEqual(db.scalar(select(func.count()).select_from(Credential)), 0)

    def test_corrected_result_credentials_supersede_previous_version(self):
        self.bootstrap()
        club = self.club()
        self.appoint_advisor(club)
        self.login("advisor")
        self.assertEqual(self.submit_role(club, self.prepare_role(club)).status_code, 201)
        self.login("student")
        competition = self.create_competition(club)
        result_path = f"/api/clubs/{club}/competitions/{competition['id']}/results"
        first = self.client.post(result_path, json=self.result_body()).json()
        self.login("advisor")
        first_path, first_prepared = self.prepare_issuance(
            club, competition["id"], first["result_id"], 1,
        )
        first_issued = self.submit_issuance(first_path, first_prepared).json()

        self.login("student")
        second = self.client.post(
            f"{result_path}/{first['result_id']}/versions",
            json=self.result_body(award="Corrected winners", proof_digest="b" * 64),
        ).json()
        self.login("advisor")
        second_path, second_prepared = self.prepare_issuance(
            club, competition["id"], second["result_id"], 2,
        )
        second_issued = self.submit_issuance(second_path, second_prepared)
        self.assertEqual(second_issued.status_code, 201, second_issued.text)
        previous_ids = {item["id"] for item in first_issued["credentials"]}
        self.assertEqual(
            {item["supersedes_id"] for item in second_issued.json()["credentials"]},
            previous_ids,
        )
        self.assertEqual(
            set(second_prepared["document"]["payload"]["superseded_credential_ids"]),
            previous_ids,
        )

    def test_invalid_advisor_inputs_do_not_write(self):
        self.bootstrap()
        club = self.club()
        for override in [
            {"email": "unverified@iitm.ac.in"}, {"email": "missing@iitm.ac.in"},
            {"email": "x@eviliitm.ac.in"}, {"public_key": "invalid"},
            {"verification_reference": " "}, {"ends_at": (self.now - timedelta(days=1)).isoformat()},
        ]:
            with self.subTest(override=override):
                self.assertEqual(self.client.post(f"/api/admin/clubs/{club}/advisors", json=self.advisor_body(**override)).status_code, 422)
        with Session(self.engine) as db:
            self.assertEqual(db.scalar(select(func.count()).select_from(Appointment)), 0)
            self.assertEqual(db.scalar(select(func.count()).select_from(AuthorityKey)), 1)

    def test_racing_appointments_reject_overlap(self):
        self.bootstrap()
        club = self.club()
        body = self.advisor_body()
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: self.client.post(f"/api/admin/clubs/{club}/advisors", json=body).status_code, range(2)))
        self.assertEqual(sorted(results), [201, 409])

    def test_key_cannot_be_reassigned_to_another_user(self):
        self.bootstrap()
        body = self.advisor_body()
        first, second = self.club(), self.club("robotics")
        self.assertEqual(self.client.post(f"/api/admin/clubs/{first}/advisors", json=body).status_code, 201)
        self.assertEqual(self.client.post(f"/api/admin/clubs/{second}/advisors", json=body | {"email": "student@iitm.ac.in"}).status_code, 409)

    def test_missing_key_and_append_failure_leave_no_partial_club(self):
        self.bootstrap()
        with patch("app.admin.load_signing_key", side_effect=ValueError("unavailable")):
            self.assertEqual(self.client.post("/api/admin/clubs", json={"name": "X", "slug": "x"}).status_code, 503)
        with patch("app.admin.append_admin_event", side_effect=RuntimeError("append failed")):
            with self.assertRaises(RuntimeError):
                self.client.post("/api/admin/clubs", json={"name": "X", "slug": "x"})
        with Session(self.engine) as db:
            self.assertEqual(db.scalar(select(func.count()).select_from(Club)), 0)

    def appoint(self, club, **overrides):
        response = self.client.post(f"/api/admin/clubs/{club}/advisors",
                                    json=self.advisor_body(**overrides))
        self.assertEqual(response.status_code, 201, response.text)
        return response.json()["id"]

    def test_revocation_records_signed_event_and_lists_the_appointment(self):
        self.bootstrap()
        appointment = self.appoint(self.club())
        listed = self.client.get("/api/ledger/revocations").json()
        self.assertEqual((listed["total"], listed["revocations"]), (0, []))
        status = self.client.get(f"/api/ledger/revocations/appointment/{appointment}").json()
        self.assertFalse(status["revoked"])

        response = self.client.post(f"/api/admin/appointments/{appointment}/revocation",
                                    json={"reason": "Advisor left the institute"})
        self.assertEqual(response.status_code, 201, response.text)
        self.assertEqual(response.json()["appointment_id"], appointment)

        with Session(self.engine) as db:
            stored = db.get(Appointment, UUID(appointment))
            self.assertIsNotNone(stored.revoked_at)
            event = db.get(FabricTransaction, stored.revocation_transaction_id)
            self.assertEqual(event.event_type, "ROLE_REVOKED")
            self.assertEqual(event.object_ids["appointment_id"], appointment)
            self.assertEqual(event.payload["reason"], "Advisor left the institute")
            key = db.get(AuthorityKey, event.actor_key_id)
            document = action_document(event.request_id, event.event_type, event.object_ids,
                                       event.payload, event.actor_id, event.actor_key_id)
            Ed25519PublicKey.from_public_bytes(key.public_key).verify(
                event.signature, rfc8785.dumps(document))

        listed = self.client.get("/api/ledger/revocations").json()
        self.assertEqual(listed["total"], 1)
        self.assertEqual(listed["revocations"][0]["subject_type"], "appointment")
        self.assertEqual(listed["revocations"][0]["subject_id"], appointment)
        self.assertTrue(self.client.get(f"/api/ledger/revocations/appointment/{appointment}").json()["revoked"])
        self.assertEqual(self.client.get("/api/admin/clubs").json()[0]["advisors"][0]["status"], "revoked")

    def test_revocation_rejects_repeat_unknown_and_elapsed_appointments(self):
        self.bootstrap()
        club = self.club()
        appointment = self.appoint(club)
        self.assertEqual(self.client.post(f"/api/admin/appointments/{uuid4()}/revocation",
                                         json={"reason": "Unknown"}).status_code, 404)
        self.assertEqual(self.client.post(f"/api/admin/appointments/{appointment}/revocation",
                                         json={"reason": " "}).status_code, 422)
        self.assertEqual(self.client.post(f"/api/admin/appointments/{appointment}/revocation",
                                         json={"reason": "First"}).status_code, 201)
        self.assertEqual(self.client.post(f"/api/admin/appointments/{appointment}/revocation",
                                         json={"reason": "Second"}).status_code, 409)
        with Session(self.engine) as db:
            self.assertEqual(db.scalar(select(func.count()).select_from(FabricTransaction)
                                       .where(FabricTransaction.event_type == "ROLE_REVOKED")), 1)
        elapsed = self.appoint(self.club("robotics"), ends_at=(self.now + timedelta(days=30)).isoformat())
        with Session(self.engine) as db, db.begin():
            # A term that has run its course; the row must keep starts_at < ends_at.
            stored = db.get(Appointment, UUID(elapsed))
            stored.starts_at = self.now - timedelta(days=2)
            stored.ends_at = self.now - timedelta(days=1)
        self.assertEqual(self.client.post(f"/api/admin/appointments/{elapsed}/revocation",
                                         json={"reason": "Already over"}).status_code, 409)

    def test_revocation_requires_admin_origin_and_live_fabric(self):
        self.bootstrap()
        appointment = self.appoint(self.club())
        self.login("student")
        self.assertEqual(self.client.post(f"/api/admin/appointments/{appointment}/revocation",
                                         json={"reason": "Not allowed"}).status_code, 403)
        self.login("admin")
        self.client.headers["Origin"] = "https://attacker.example"
        self.assertEqual(self.client.post(f"/api/admin/appointments/{appointment}/revocation",
                                         json={"reason": "Cross origin"}).status_code, 403)
        self.client.headers["Origin"] = "http://127.0.0.1:5173"
        with patch("app.admin.append_admin_event", side_effect=FabricUnavailable("down")):
            self.assertEqual(self.client.post(f"/api/admin/appointments/{appointment}/revocation",
                                             json={"reason": "Fabric down"}).status_code, 503)
        with Session(self.engine) as db:
            self.assertIsNone(db.get(Appointment, UUID(appointment)).revoked_at)
        self.assertEqual(self.client.get("/api/ledger/revocations").json()["total"], 0)

    def test_racing_revocations_append_one_event(self):
        self.bootstrap()
        appointment = self.appoint(self.club())
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: self.client.post(
                f"/api/admin/appointments/{appointment}/revocation",
                json={"reason": "Concurrent"}).status_code, range(2)))
        self.assertEqual(sorted(results), [201, 409])
        with Session(self.engine) as db:
            self.assertEqual(db.scalar(select(func.count()).select_from(FabricTransaction)
                                       .where(FabricTransaction.event_type == "ROLE_REVOKED")), 1)
        self.assertEqual(self.client.get("/api/ledger/revocations").json()["total"], 1)

    def test_revoked_advisor_term_frees_the_club_for_a_new_appointment(self):
        self.bootstrap()
        club = self.club()
        appointment = self.appoint(club)
        # A fresh term, as an annual handover would use: the appointment ID is
        # derived from the club, user and term, so a repeated term collides.
        successor = self.advisor_body(ends_at=(self.now + timedelta(days=700)).isoformat())
        self.assertEqual(self.client.post(f"/api/admin/clubs/{club}/advisors", json=successor).status_code, 409)
        self.assertEqual(self.client.post(f"/api/admin/appointments/{appointment}/revocation",
                                         json={"reason": "Handover"}).status_code, 201)
        self.assertEqual(self.client.post(f"/api/admin/clubs/{club}/advisors", json=successor).status_code, 201)

    def test_revoked_admin_is_denied(self):
        self.bootstrap()
        with Session(self.engine) as db, db.begin():
            db.get(Administrator, 1).revoked_at = self.now
        self.assertEqual(self.client.get("/api/admin/access").status_code, 403)
        with self.assertRaises(ValueError):
            self.bootstrap()
