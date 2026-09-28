import base64
import hashlib
import os
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

import rfc8785
from alembic import command
from alembic.config import Config
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, func, select, text
from sqlalchemy.orm import Session

from app.admin import admin_settings
from app.authority import action_document, entry_document
from app.bootstrap import bootstrap_admin
from app.config import Settings
from app.database import get_session
from app.main import app
from app.models import Administrator, Appointment, AuthorityKey, Club, LedgerEntry, User
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
        self.settings = Settings(_env_file=None, admin_signing_key_file=self.key_path)
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
            return bootstrap_admin(db, f"{name}@iitm.ac.in", "Institutional approval 001", self.private_key).user_id

    def club(self, slug="coding"):
        response = self.client.post("/api/admin/clubs", json={"name": slug.title(), "slug": slug})
        self.assertEqual(response.status_code, 201, response.text)
        return response.json()["id"]

    def advisor_body(self, **overrides):
        return dict(email="advisor@iitm.ac.in", public_key=base64.b64encode(
            Ed25519PrivateKey.generate().public_key().public_bytes_raw()).decode(),
            verification_reference="Faculty directory confirmation 002",
            starts_at=(self.now - timedelta(minutes=1)).isoformat(),
            ends_at=(self.now + timedelta(days=365)).isoformat()) | overrides

    def test_bootstrap_verified_only_and_idempotent(self):
        with self.assertRaises(ValueError):
            self.bootstrap("unverified")
        self.assertEqual(self.bootstrap(), self.users["admin"])
        self.assertEqual(self.bootstrap(), self.users["admin"])
        with self.assertRaises(ValueError):
            self.bootstrap("student")
        with Session(self.engine) as db:
            self.assertEqual(db.scalar(select(func.count()).select_from(Administrator)), 1)
            self.assertEqual(db.scalar(select(func.count()).select_from(LedgerEntry)), 1)

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
            self.assertEqual(db.scalar(select(func.count()).select_from(LedgerEntry)), 2)

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
            previous_hash = None
            for sequence, entry in enumerate(db.scalars(select(LedgerEntry).order_by(LedgerEntry.sequence)), 1):
                self.assertEqual(entry.sequence, sequence)
                self.assertEqual(entry.previous_entry_hash, previous_hash)
                key = db.get(AuthorityKey, entry.actor_key_id)
                Ed25519PublicKey.from_public_bytes(key.public_key).verify(entry.signature, rfc8785.dumps(action_document(entry)))
                self.assertEqual(entry.entry_hash, hashlib.sha256(rfc8785.dumps(entry_document(entry))).hexdigest())
                previous_hash = entry.entry_hash
        self.assertEqual(self.client.get("/api/admin/clubs").json()[0]["advisors"][0]["status"], "active")
        self.login("advisor")
        self.assertEqual(self.client.get("/api/admin/access").status_code, 403)

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

    def test_revoked_admin_is_denied(self):
        self.bootstrap()
        with Session(self.engine) as db, db.begin():
            db.get(Administrator, 1).revoked_at = self.now
        self.assertEqual(self.client.get("/api/admin/access").status_code, 403)
        with self.assertRaises(ValueError):
            self.bootstrap()
