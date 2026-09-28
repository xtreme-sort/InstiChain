import os
import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, func, select, text, update
from sqlalchemy.orm import Session as DbSession

from app.config import Settings
from app.database import get_session
from app.main import app
from app.models import EmailVerification, Session, User
from app.session import SESSION_COOKIE, cookie_kwargs, session_settings
from app.verification import verification_settings


class CookieAttributeTests(unittest.TestCase):
    def test_secure_flag_follows_public_app_url_scheme(self):
        http_settings = Settings(_env_file=None)
        https_settings = Settings(_env_file=None, public_app_url="https://instichain.example")
        self.assertFalse(cookie_kwargs(http_settings)["secure"])
        self.assertTrue(cookie_kwargs(https_settings)["secure"])
        self.assertTrue(cookie_kwargs(http_settings)["httponly"])
        self.assertEqual(cookie_kwargs(http_settings)["samesite"], "lax")


@unittest.skipUnless(os.getenv("INSTICHAIN_TEST_DATABASE_URL"), "Set INSTICHAIN_TEST_DATABASE_URL to run PostgreSQL tests")
class SessionTests(unittest.TestCase):
    def setUp(self):
        self.schema = "test_session_" + uuid4().hex
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
            with DbSession(self.engine) as session:
                yield session

        app.dependency_overrides[get_session] = session_dependency
        app.dependency_overrides[verification_settings] = lambda: Settings(_env_file=None)
        app.dependency_overrides[session_settings] = lambda: Settings(_env_file=None)
        self.addCleanup(app.dependency_overrides.clear)
        self.client = TestClient(app)
        self.addCleanup(self.client.close)
        self.sent = []
        self.mail_patch = patch(
            "app.verification.send_verification_email",
            side_effect=lambda email, token, settings, purpose="verify": self.sent.append((email, token, purpose)),
        )
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

    def test_confirm_sets_working_session_cookie(self):
        self.request()
        response = self.confirm()
        self.assertEqual(response.status_code, 200)
        self.assertIn(SESSION_COOKIE, response.cookies)
        session_response = self.client.get("/api/auth/session")
        self.assertEqual(session_response.status_code, 200)
        body = session_response.json()
        self.assertEqual(body["email"], "student@smail.iitm.ac.in")
        self.assertEqual(body["display_name"], "Student")

    def test_verified_user_can_request_login_link_and_sign_in(self):
        self.request()
        self.confirm()
        self.client.cookies.clear()
        self.age_requests()
        sent_before = len(self.sent)
        response = self.request()
        self.assertEqual(response.status_code, 202)
        self.assertEqual(len(self.sent), sent_before + 1)
        self.assertEqual(self.sent[-1][2], "login")
        login = self.confirm()
        self.assertEqual(login.status_code, 200)
        self.assertIn(SESSION_COOKIE, login.cookies)

    def test_logout_revokes_session(self):
        self.request()
        self.confirm()
        self.assertEqual(self.client.post("/api/auth/logout").status_code, 204)
        self.assertEqual(self.client.get("/api/auth/session").status_code, 401)
        with DbSession(self.engine) as session:
            record = session.scalar(select(Session))
            self.assertIsNotNone(record.revoked_at)

    def test_registration_inspection_does_not_consume_link(self):
        self.request()
        token = self.sent[-1][1]
        for _ in range(2):
            response = self.client.post("/api/auth/email/inspect", json={"token": token})
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json(), {"requires_name": True})
            self.assertEqual(response.headers["cache-control"], "no-store")
            self.assertNotIn(SESSION_COOKIE, response.cookies)
        self.assertEqual(self.confirm(token).status_code, 200)
        self.assertEqual(self.client.post("/api/auth/email/inspect", json={"token": token}).status_code, 400)

    def test_new_account_requires_name_without_consuming_token(self):
        self.request()
        token = self.sent[-1][1]
        response = self.client.post("/api/auth/email/confirm", json={"token": token})
        self.assertEqual(response.status_code, 422)
        with DbSession(self.engine) as session:
            self.assertEqual(session.scalar(select(func.count()).select_from(User)), 0)
            self.assertIsNone(session.scalar(select(EmailVerification.consumed_at)))
        self.assertEqual(self.confirm(token).status_code, 200)

    def test_returning_login_needs_no_name_and_preserves_identity(self):
        self.request()
        self.confirm(display_name="Arjun")
        original = self.client.get("/api/auth/session").json()
        self.client.post("/api/auth/logout")
        self.age_requests()
        self.request()
        token = self.sent[-1][1]
        response = self.client.post("/api/auth/email/inspect", json={"token": token})
        self.assertEqual(response.json(), {"requires_name": False})
        self.assertEqual(self.client.post("/api/auth/email/confirm", json={"token": token}).status_code, 200)
        self.assertEqual(self.client.get("/api/auth/session").json(), original)

    def test_login_cannot_overwrite_saved_name(self):
        self.request()
        self.confirm(display_name="Arjun")
        original = self.client.get("/api/auth/session").json()
        self.client.post("/api/auth/logout")
        self.age_requests()
        self.request()
        self.assertEqual(self.confirm(display_name="Rahul").status_code, 200)
        self.assertEqual(self.client.get("/api/auth/session").json(), original)

    def test_inspection_rejects_expired_unknown_and_superseded_links(self):
        self.request()
        first = self.sent[-1][1]
        self.age_requests()
        self.request()
        current = self.sent[-1][1]
        with self.engine.begin() as connection:
            connection.execute(update(EmailVerification).values(
                created_at=func.now() - text("interval '20 minutes'"),
                expires_at=func.now() - text("interval '1 second'"),
            ))
        for token in [first, current, "z" * 43]:
            with self.subTest(token=token):
                self.assertEqual(self.client.post("/api/auth/email/inspect", json={"token": token}).status_code, 400)

    def test_missing_or_unknown_cookie_is_unauthorized(self):
        self.assertEqual(self.client.get("/api/auth/session").status_code, 401)
        self.client.cookies.set(SESSION_COOKIE, "x" * 43)
        self.assertEqual(self.client.get("/api/auth/session").status_code, 401)

    def test_expired_session_is_rejected(self):
        self.request()
        self.confirm()
        with self.engine.begin() as connection:
            connection.execute(update(Session).values(
                created_at=func.now() - text("interval '2 seconds'"),
                expires_at=func.now() - text("interval '1 second'"),
            ))
        self.assertEqual(self.client.get("/api/auth/session").status_code, 401)

    def test_logout_without_cookie_is_idempotent(self):
        self.assertEqual(self.client.post("/api/auth/logout").status_code, 204)

    def test_login_token_is_single_use(self):
        self.request()
        self.confirm()
        self.client.cookies.clear()
        self.age_requests()
        self.request()
        token = self.sent[-1][1]
        self.assertEqual(self.confirm(token).status_code, 200)
        self.assertEqual(self.confirm(token).status_code, 400)

    def test_concurrent_login_confirmation_has_exactly_one_success(self):
        self.request()
        self.confirm()
        self.client.cookies.clear()
        self.age_requests()
        self.request()
        token = self.sent[-1][1]
        with ThreadPoolExecutor(max_workers=2) as pool:
            responses = list(pool.map(lambda _: self.confirm(token).status_code, range(2)))
        self.assertEqual(sorted(responses), [200, 400])
        with DbSession(self.engine) as session:
            self.assertEqual(session.scalar(select(func.count()).select_from(User)), 1)


if __name__ == "__main__":
    unittest.main()
