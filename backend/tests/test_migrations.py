"""PostgreSQL integration tests; each run owns a temporary, isolated schema."""

import os
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4

from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.config import Config
from alembic.migration import MigrationContext
from sqlalchemy import create_engine, func, inspect, select, text
from sqlalchemy.exc import DBAPIError, IntegrityError

from app.database import Base
from app.models import Appointment, Club, Competition, Credential, LedgerEntry, User


@unittest.skipUnless(os.getenv("INSTICHAIN_TEST_DATABASE_URL"), "Set INSTICHAIN_TEST_DATABASE_URL to run PostgreSQL tests")
class MigrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.engine = create_engine(os.environ["INSTICHAIN_TEST_DATABASE_URL"])
        cls.addClassCleanup(cls.engine.dispose)
        cls.connection = cls.engine.connect()
        cls.addClassCleanup(cls.connection.close)
        cls.schema = "test_instichain_" + uuid4().hex
        cls.connection.execute(text(f'CREATE SCHEMA "{cls.schema}"'))
        cls.connection.execute(text(f'SET search_path TO "{cls.schema}"'))
        cls.connection.commit()
        cls.addClassCleanup(cls.drop_schema)
        # Match reflection's default schema to this connection's search path.
        cls.engine.dialect.default_schema_name = cls.schema
        cls.config = Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))
        cls.config.attributes["connection"] = cls.connection
        command.upgrade(cls.config, "head")
        cls.connection.commit()

    @classmethod
    def drop_schema(cls):
        cls.connection.rollback()
        cls.connection.execute(text(f'DROP SCHEMA "{cls.schema}" CASCADE'))
        cls.connection.commit()

    def setUp(self):
        self.transaction = self.connection.begin()
        self.addCleanup(self.transaction.rollback)
        self.now = datetime.now(timezone.utc)
        self.actor = self.insert(User, email="advisor@iitm.ac.in", display_name="Advisor")
        self.student = self.insert(User, email="student@smail.iitm.ac.in", display_name="Student")
        self.club = self.insert(Club, slug="coding-club", name="Coding Club", created_by_id=self.actor)
        self.competition = self.insert(
            Competition, club_id=self.club, title="Programming Contest",
            starts_at=self.now, created_by_id=self.actor,
        )
        self.sequence = 0

    def insert(self, model, **values):
        return self.connection.execute(model.__table__.insert().values(**values).returning(model.id)).scalar_one()

    def ledger_values(self, **overrides):
        self.sequence += 1
        values = dict(
            sequence=self.sequence, transaction_id=uuid4(), event_type="ACHIEVEMENT_ISSUED",
            object_ids={"club_id": str(self.club)}, payload={}, actor_id=self.actor,
            actor_key_id=uuid4(), signature=b"x" * 64,
            previous_entry_hash=None if self.sequence == 1 else f"{self.sequence - 1:064x}",
            entry_hash=f"{self.sequence:064x}",
        )
        return values | overrides

    def append(self, **overrides):
        values = self.ledger_values(**overrides)
        self.connection.execute(LedgerEntry.__table__.insert().values(**values))
        return values["transaction_id"]

    def credential_values(self, **overrides):
        return dict(
            competition_id=self.competition, recipient_id=self.student, advisor_id=self.actor,
            award="First place", result_id=uuid4(), submission_version=1,
            proof_digest="a" * 64, issuance_transaction_id=self.append(), issued_at=self.now,
        ) | overrides

    def reject(self, statement, constraint=None, error=IntegrityError):
        with self.assertRaises(error) as caught:
            with self.connection.begin_nested():
                self.connection.execute(statement)
        if constraint:
            self.assertEqual(caught.exception.orig.diag.constraint_name, constraint)

    def test_schema_matches_models_and_upgrade_is_repeatable(self):
        command.upgrade(self.config, "head")
        self.assertEqual(compare_metadata(MigrationContext.configure(self.connection), Base.metadata), [])
        self.assertEqual(set(inspect(self.connection).get_table_names()), set(Base.metadata.tables) | {"alembic_version"})

    def test_downgrade_and_reapply(self):
        command.downgrade(self.config, "base")
        self.assertEqual(inspect(self.connection).get_table_names(), ["alembic_version"])
        self.assertIsNone(self.connection.scalar(text("SELECT to_regprocedure('reject_ledger_mutation()')")))
        command.upgrade(self.config, "head")
        self.assertEqual(compare_metadata(MigrationContext.configure(self.connection), Base.metadata), [])

    def test_email_uniqueness_and_normalization(self):
        for email, constraint in [
            ("student@smail.iitm.ac.in", "uq_users_email"),
            ("Student@smail.iitm.ac.in", "ck_users_normalized_email"),
            (" student@smail.iitm.ac.in ", "ck_users_normalized_email"),
        ]:
            with self.subTest(email=email):
                self.reject(User.__table__.insert().values(email=email, display_name="Duplicate"), constraint)

    def test_verification_upgrade_preserves_existing_users(self):
        command.downgrade(self.config, "0001")
        command.upgrade(self.config, "head")
        self.assertEqual(self.connection.scalar(select(User.id).where(User.id == self.student)), self.student)

    def test_appointment_term_position_and_revocation_reference(self):
        values = dict(
            club_id=self.club, user_id=self.student, position="club_head", granted_by_id=self.actor,
            starts_at=self.now, ends_at=self.now + timedelta(days=365),
            grant_transaction_id=self.append(event_type="ROLE_GRANTED"),
        )
        for override, constraint in [
            ({"ends_at": self.now}, "ck_appointments_term_order"),
            ({"position": "admin"}, "ck_appointments_position"),
            ({"revoked_at": self.now + timedelta(days=1)}, "ck_appointments_revocation_reference"),
        ]:
            with self.subTest(constraint=constraint):
                self.reject(Appointment.__table__.insert().values(**(values | override)), constraint)
        appointment = self.insert(Appointment, **values)
        stored = self.connection.scalar(select(Appointment.starts_at).where(Appointment.id == appointment))
        self.assertIsNotNone(stored.tzinfo)

    def test_competition_dates(self):
        self.reject(Competition.__table__.insert().values(
            club_id=self.club, title="Invalid", created_by_id=self.actor,
            starts_at=self.now, ends_at=self.now - timedelta(days=1),
        ), "ck_competitions_date_order")

    def test_team_credentials_and_duplicate_recipient(self):
        values = self.credential_values()
        self.insert(Credential, **values)
        other = self.insert(User, email="other@iitm.ac.in", display_name="Other")
        self.insert(Credential, **(values | {"recipient_id": other, "issuance_transaction_id": self.append()}))
        self.reject(Credential.__table__.insert().values(**(values | {"issuance_transaction_id": self.append()})),
                    "uq_credentials_result_version_recipient")
        self.assertEqual(self.connection.scalar(select(func.count()).select_from(Credential)), 2)

    def test_invalid_digest_and_missing_issuance(self):
        values = self.credential_values()
        self.reject(Credential.__table__.insert().values(**(values | {"proof_digest": "invalid"})),
                    "ck_credentials_proof_digest_format")
        self.reject(Credential.__table__.insert().values(**(values | {"issuance_transaction_id": uuid4()})),
                    "fk_credentials_issuance_transaction_id_ledger_entries")

    def test_references_prevent_orphans_and_cascade_deletion(self):
        self.reject(Club.__table__.insert().values(slug="missing", name="Missing", created_by_id=uuid4()),
                    "fk_clubs_created_by_id_users")
        self.reject(User.__table__.delete().where(User.id == self.actor), "fk_clubs_created_by_id_users")

    def test_ledger_shape_constraints(self):
        cases = [
            ({"sequence": 0}, "ck_ledger_entries_positive_sequence"),
            ({"signature": b"short"}, "ck_ledger_entries_signature_length"),
            ({"entry_hash": "invalid"}, "ck_ledger_entries_entry_hash_format"),
            ({"previous_entry_hash": None}, "ck_ledger_entries_previous_hash_format"),
            ({"schema_version": 0}, "ck_ledger_entries_positive_schema_version"),
            ({"payload": []}, "ck_ledger_entries_payload_object"),
        ]
        for overrides, constraint in cases:
            with self.subTest(constraint=constraint):
                values = self.ledger_values(**overrides)
                self.reject(LedgerEntry.__table__.insert().values(**values), constraint)

    def test_ledger_rejects_mutation(self):
        self.append()
        for statement in [
            LedgerEntry.__table__.update().values(event_type="CHANGED"),
            LedgerEntry.__table__.delete(),
            text("TRUNCATE ledger_entries CASCADE"),
        ]:
            with self.subTest(statement=str(statement)):
                self.reject(statement, error=DBAPIError)
        self.assertEqual(self.connection.scalar(select(func.count()).select_from(LedgerEntry)), 1)


if __name__ == "__main__":
    unittest.main()
