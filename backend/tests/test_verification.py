import hashlib
import os
import smtplib
import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, func, select, text, update
from sqlalchemy.orm import Session

from app.config import Settings
from app.database import get_session
from app.email_delivery import send_verification_email
from app.main import app
from app.models import Appointment, EmailVerification, User
from app.verification import normalize_institute_email, verification_settings


class EmailValidationTests(unittest.TestCase):
    def test_institute_domains_and_normalization(self):
        for email in ["User@IITM.AC.IN", " user@smail.iitm.ac.in ", "user@dept.smail.iitm.ac.in"]:
            with self.subTest(email=email):
                self.assertEqual(normalize_institute_email(email), email.strip().lower())

    def test_lookalikes_and_malformed_addresses(self):
        for email in [
            "user@eviliitm.ac.in", "user@iitm.ac.in.evil.com", "user@iitm-ac.in",
            "user@gmail.com", "user@@iitm.ac.in", "user@.iitm.ac.in", "@iitm.ac.in",
            "a..b@iitm.ac.in", "user@iitm.ac.in\r\nBcc: evil@example.com",
            "user@iit\u043c.ac.in", "user@iitm.ac.in.", "user@iitm..ac.in",
        ]:
            with self.subTest(email=email), self.assertRaises(ValueError):
                normalize_institute_email(email)

    def test_smtp_message_and_tls(self):
        settings = Settings(_env_file=None, smtp_security="starttls", smtp_port=587,
                            smtp_username="sender", smtp_password="test-only")
        with patch("app.email_delivery.smtplib.SMTP") as smtp:
            send_verification_email("student@iitm.ac.in", "x" * 43, settings)
            client = smtp.return_value.__enter__.return_value
            client.starttls.assert_called_once()
            client.login.assert_called_once_with("sender", "test-only")
            message = client.send_message.call_args.args[0]
            self.assertEqual(message["To"], "student@iitm.ac.in")
            self.assertIn("/verify-email#token=" + "x" * 43, message.get_content())
            self.assertIn("15 minutes", message.get_content())


@unittest.skipUnless(os.getenv("INSTICHAIN_TEST_DATABASE_URL"), "Set INSTICHAIN_TEST_DATABASE_URL to run PostgreSQL tests")
class VerificationTests(unittest.TestCase):
    def setUp(self):
        self.schema = "test_verification_" + uuid4().hex
        self.root_engine = create_engine(os.environ["INSTICHAIN_TEST_DATABASE_URL"])
        self.addCleanup(self.root_engine.dispose)
        with self.root_engine.begin() as connection:
            connection.execute(text(f'CREATE SCHEMA "{self.schema}"'))
        self.addCleanup(self.drop_schema)
        self.engine = create_engine(os.environ["INSTICHAIN_TEST_DATABASE_URL"],
                                    connect_args={"options": f"-csearch_path={self.schema}"})
        self.addCleanup(self.engine.dispose)
        config = Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))
        with self.engine.begin() as connection:
            config.attributes["connection"] = connection
            command.upgrade(config, "head")

        def session_dependency():
            with Session(self.engine) as session:
                yield session

        app.dependency_overrides[get_session] = session_dependency
        app.dependency_overrides[verification_settings] = lambda: Settings(_env_file=None)
        self.addCleanup(app.dependency_overrides.clear)
        self.client = TestClient(app)
        self.addCleanup(self.client.close)
        self.sent = []
        self.mail_patch = patch("app.verification.send_verification_email", side_effect=lambda email, token, settings: self.sent.append((email, token)))
        self.sender = self.mail_patch.start()
        self.addCleanup(self.mail_patch.stop)

    def drop_schema(self):
        with self.root_engine.begin() as connection:
            connection.execute(text(f'DROP SCHEMA "{self.schema}" CASCADE'))

    def request(self, email="student@smail.iitm.ac.in"):
        return self.client.post("/api/auth/email/request", json={"email": email})

    def confirm(self, token=None, **extras):
        return self.client.post("/api/auth/email/confirm", json={
            "token": token or self.sent[-1][1], "display_name": "Student", **extras,
        })

    def age_requests(self):
        with self.engine.begin() as connection:
            connection.execute(update(EmailVerification).values(created_at=EmailVerification.created_at - timedelta(minutes=2)))

    def test_request_stores_hash_without_creating_account(self):
        response = self.request(" Student@SMAIL.IITM.AC.IN ")
        self.assertEqual(response.status_code, 202)
        self.assertEqual(response.headers["cache-control"], "no-store")
        email, token = self.sent[0]
        self.assertEqual(email, "student@smail.iitm.ac.in")
        self.assertNotIn(token, response.text)
        with Session(self.engine) as session:
            record = session.scalar(select(EmailVerification))
            self.assertEqual(record.token_hash, hashlib.sha256(token.encode()).hexdigest())
            self.assertEqual(record.expires_at - record.created_at, timedelta(minutes=15))
            self.assertEqual(session.scalar(select(func.count()).select_from(User)), 0)

    def test_confirmation_is_single_use_and_grants_no_roles(self):
        self.request()
        self.assertEqual(self.confirm().status_code, 200)
        self.assertEqual(self.confirm().status_code, 400)
        with Session(self.engine) as session:
            user = session.scalar(select(User))
            self.assertIsNotNone(user.email_verified_at)
            self.assertEqual(user.display_name, "Student")
            self.assertEqual(session.scalar(select(func.count()).select_from(Appointment)), 0)
            self.assertIsNotNone(session.scalar(select(EmailVerification.consumed_at)))

    def test_expired_and_unknown_tokens_are_rejected(self):
        self.request()
        with self.engine.begin() as connection:
            connection.execute(update(EmailVerification).values(
                created_at=func.now() - text("interval '20 minutes'"),
                expires_at=func.now() - text("interval '1 second'"),
            ))
        expired = self.confirm()
        unknown = self.confirm("z" * 43)
        self.assertEqual(expired.status_code, 400)
        self.assertEqual(expired.json(), unknown.json())
        with Session(self.engine) as session:
            self.assertEqual(session.scalar(select(func.count()).select_from(User)), 0)

    def test_resend_throttle_and_old_link_invalidation(self):
        first = self.request()
        old_token = self.sent[0][1]
        self.assertEqual(self.request().json(), first.json())
        self.assertEqual(len(self.sent), 1)
        self.age_requests()
        self.assertEqual(self.request().status_code, 202)
        self.assertEqual(len(self.sent), 2)
        self.assertEqual(self.confirm(old_token).status_code, 400)
        self.assertEqual(self.confirm().status_code, 200)

    def test_daily_limit(self):
        for _ in range(6):
            self.request()
            self.age_requests()
        self.assertEqual(len(self.sent), 5)

    def test_smtp_failure_rolls_back(self):
        self.request()
        old_token = self.sent[0][1]
        self.age_requests()
        self.sender.side_effect = smtplib.SMTPException("delivery failed")
        self.assertEqual(self.request().status_code, 503)
        self.assertEqual(self.confirm(old_token).status_code, 200)
        with Session(self.engine) as session:
            self.assertEqual(session.scalar(select(func.count()).select_from(EmailVerification)), 1)

    def test_existing_verified_account_is_not_changed_or_disclosed(self):
        first = self.request()
        self.confirm()
        self.assertEqual(self.request().json(), first.json())
        self.assertEqual(len(self.sent), 1)

    def test_existing_unverified_account_keeps_its_id(self):
        user_id = uuid4()
        with self.engine.begin() as connection:
            connection.execute(User.__table__.insert().values(id=user_id, email="student@smail.iitm.ac.in", display_name="Before"))
        self.request()
        self.assertEqual(self.confirm().status_code, 200)
        with Session(self.engine) as session:
            self.assertEqual(session.scalar(select(User.id)), user_id)
            self.assertEqual(session.scalar(select(User.display_name)), "Student")

    def test_invalid_email_and_role_injection_rejected(self):
        self.assertEqual(self.request("student@eviliitm.ac.in").status_code, 422)
        self.assertEqual(self.sent, [])
        self.request()
        self.assertEqual(self.confirm(role="faculty_advisor").status_code, 422)
        self.assertEqual(self.confirm(display_name="   ").status_code, 422)
        self.assertEqual(self.confirm().status_code, 200)

    def test_get_cannot_consume_token(self):
        self.request()
        self.assertEqual(self.client.get("/api/auth/email/confirm").status_code, 405)
        self.assertEqual(self.confirm().status_code, 200)

    def test_concurrent_confirmation_has_exactly_one_success(self):
        self.request()
        with ThreadPoolExecutor(max_workers=2) as pool:
            responses = list(pool.map(lambda _: self.confirm().status_code, range(2)))
        self.assertEqual(sorted(responses), [200, 400])
        with Session(self.engine) as session:
            self.assertEqual(session.scalar(select(func.count()).select_from(User)), 1)

    def test_concurrent_requests_send_one_email(self):
        with ThreadPoolExecutor(max_workers=2) as pool:
            responses = list(pool.map(lambda _: self.request().status_code, range(2)))
        self.assertEqual(responses, [202, 202])
        self.assertEqual(len(self.sent), 1)
