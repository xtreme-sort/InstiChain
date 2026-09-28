"""Server-signed administration events; advisor credential signing comes later."""

import base64
import hashlib
from datetime import timezone
from pathlib import Path
from uuid import uuid4

import rfc8785
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from sqlalchemy import func, select, text
from sqlalchemy.orm import Session

from app.models import AuthorityKey, LedgerEntry

LEDGER_LOCK = 50130001


def lock_ledger(db: Session) -> None:
    db.execute(text("SELECT pg_advisory_xact_lock(:key)"), {"key": LEDGER_LOCK})


def load_signing_key(path: Path | None) -> Ed25519PrivateKey:
    if path is None:
        raise ValueError("Configure INSTICHAIN_ADMIN_SIGNING_KEY_FILE first.")
    if path.stat().st_mode & 0o077:
        raise ValueError("The signing key must be readable only by its owner (chmod 600).")
    key = serialization.load_pem_private_key(path.read_bytes(), password=None)
    if not isinstance(key, Ed25519PrivateKey):
        raise ValueError("The signing key must be Ed25519.")
    return key


def action_document(entry: LedgerEntry) -> dict:
    return {
        "schema_version": entry.schema_version,
        "transaction_id": str(entry.transaction_id), "event_type": entry.event_type,
        "object_ids": entry.object_ids, "payload": entry.payload,
        "actor_id": str(entry.actor_id), "actor_key_id": str(entry.actor_key_id),
    }


def entry_document(entry: LedgerEntry) -> dict:
    return action_document(entry) | {
        "sequence": entry.sequence,
        "signature": base64.b64encode(entry.signature).decode("ascii"),
        "committed_at": entry.committed_at.astimezone(timezone.utc).isoformat(),
        "previous_entry_hash": entry.previous_entry_hash,
    }


def append_admin_event(db: Session, key: AuthorityKey, private_key: Ed25519PrivateKey,
                       event_type: str, object_ids: dict, payload: dict) -> LedgerEntry:
    lock_ledger(db)
    if key.purpose != "administrator" or key.revoked_at is not None:
        raise ValueError("Administrator signing key is inactive.")
    if private_key.public_key().public_bytes_raw() != key.public_key:
        raise ValueError("Configured signing key does not match the registered key.")
    previous = db.scalar(select(LedgerEntry).order_by(LedgerEntry.sequence.desc()).limit(1))
    entry = LedgerEntry(
        sequence=previous.sequence + 1 if previous else 1, transaction_id=uuid4(),
        schema_version=1, event_type=event_type, object_ids=object_ids, payload=payload,
        actor_id=key.user_id, actor_key_id=key.id,
        committed_at=db.scalar(select(func.clock_timestamp())),
        previous_entry_hash=previous.entry_hash if previous else None,
    )
    entry.signature = private_key.sign(rfc8785.dumps(action_document(entry)))
    entry.entry_hash = hashlib.sha256(rfc8785.dumps(entry_document(entry))).hexdigest()
    db.add(entry)
    db.flush()
    return entry
