"""Operator-only commands. No HTTP endpoint can bootstrap an administrator."""

import argparse
import base64
import getpass
import os
from pathlib import Path
from uuid import uuid4

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from sqlalchemy import select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from app.authority import append_admin_event, load_signing_key, lock_ledger
from app.config import Settings
from app.database import get_engine
from app.models import Administrator, AuthorityKey, User
from app.verification import normalize_institute_email


def bootstrap_admin(db: Session, email: str, reference: str, private_key: Ed25519PrivateKey) -> Administrator:
    email = normalize_institute_email(email)
    reference = reference.strip()
    if not 1 <= len(reference) <= 500:
        raise ValueError("Provide an institutional verification reference (1-500 characters).")
    lock_ledger(db)
    user = db.scalar(select(User).where(User.email == email))
    if user is None or user.email_verified_at is None:
        raise ValueError("The administrator must have an existing verified institute account.")
    existing = db.get(Administrator, 1)
    if existing:
        key = db.get(AuthorityKey, existing.key_id)
        if (existing.user_id == user.id and existing.revoked_at is None
                and key.revoked_at is None and key.public_key == private_key.public_key().public_bytes_raw()):
            return existing
        raise ValueError("Bootstrap is already complete. It cannot replace or add administrators.")
    key = AuthorityKey(id=uuid4(), user_id=user.id, public_key=private_key.public_key().public_bytes_raw(),
                       purpose="administrator", verified_by_id=user.id, verification_reference=reference)
    db.add(key)
    db.flush()
    event = append_admin_event(db, key, private_key, "ADMIN_BOOTSTRAPPED", {"user_id": str(user.id)},
                               {"public_key": base64.b64encode(key.public_key).decode("ascii")})
    admin = Administrator(id=1, user_id=user.id, key_id=key.id, bootstrap_transaction_id=event.transaction_id)
    db.add(admin)
    db.flush()
    return admin


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    generate = commands.add_parser("keygen", help="Create a private server signing key without overwriting files")
    generate.add_argument("--output", type=Path, required=True)
    advisor_generate = commands.add_parser("advisor-keygen", help="Generate an encrypted advisor key on the advisor's own workstation")
    advisor_generate.add_argument("--output", type=Path, required=True)
    bootstrap = commands.add_parser("admin", help="Establish the single initial institute administrator")
    bootstrap.add_argument("--email", required=True)
    bootstrap.add_argument("--verification-reference", required=True)
    args = parser.parse_args()
    try:
        if args.command in {"keygen", "advisor-keygen"}:
            private_key = Ed25519PrivateKey.generate()
            encryption = serialization.NoEncryption()
            if args.command == "advisor-keygen":
                password = getpass.getpass("Advisor key passphrase (at least 12 characters): ")
                if len(password) < 12 or password != getpass.getpass("Repeat passphrase: "):
                    raise ValueError("Passphrases must match and contain at least 12 characters.")
                encryption = serialization.BestAvailableEncryption(password.encode())
            args.output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "wb") as output:
                output.write(private_key.private_bytes(serialization.Encoding.PEM,
                             serialization.PrivateFormat.PKCS8, encryption))
            print("Signing key created. Keep it private and back it up securely.")
            print("Public key (base64): " + base64.b64encode(private_key.public_key().public_bytes_raw()).decode("ascii"))
            return
        private_key = load_signing_key(Settings().admin_signing_key_file)
        with Session(get_engine()) as db, db.begin():
            admin = bootstrap_admin(db, args.email, args.verification_reference, private_key)
            user_id = admin.user_id
        print(f"Administrator ready: {user_id}")
    except (ValueError, OSError) as exc:
        parser.exit(1, f"{exc}\n")
    except SQLAlchemyError:
        parser.exit(1, "Database operation failed. Check connectivity and apply migrations.\n")


if __name__ == "__main__":
    main()
