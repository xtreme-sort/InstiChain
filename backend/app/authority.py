"""Administrator signatures and Hyperledger Fabric transaction submission."""

import base64
from datetime import datetime
from pathlib import Path
from uuid import UUID

import httpx
import rfc8785
from cryptography.hazmat.primitives import serialization
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.config import Settings
from app.models import AuthorityKey, FabricTransaction

LEDGER_LOCK = 50130001


class FabricUnavailable(RuntimeError):
    """Raised when a transaction cannot be confirmed on Fabric."""


def lock_ledger(db: Session) -> None:
    """Serialize local projections; Fabric provides authoritative ordering."""
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


def action_document(request_id: UUID, event_type: str, object_ids: dict, payload: dict,
                    actor_id: UUID, actor_key_id: UUID) -> dict:
    return {
        "request_id": str(request_id),
        "event_type": event_type,
        "object_ids": object_ids,
        "payload": payload,
        "actor_id": str(actor_id),
        "actor_key_id": str(actor_key_id),
    }


class FabricClient:
    def __init__(self, settings: Settings, client: httpx.Client | None = None):
        self.settings = settings
        self.client = client

    def submit(self, document: dict) -> dict:
        secret = self.settings.fabric_bridge_token
        if secret is None:
            raise FabricUnavailable("Configure INSTICHAIN_FABRIC_BRIDGE_TOKEN first.")
        url = str(self.settings.fabric_bridge_url).rstrip("/") + "/transactions"

        def send(client: httpx.Client) -> httpx.Response:
            return client.post(url, json=document, headers={
                "Authorization": f"Bearer {secret.get_secret_value()}",
            })

        try:
            if self.client is not None:
                response = send(self.client)
            else:
                with httpx.Client(timeout=70, follow_redirects=False) as client:
                    response = send(client)
            response.raise_for_status()
            receipt = response.json()
        except (httpx.HTTPError, ValueError, OSError) as exc:
            raise FabricUnavailable("Fabric did not confirm the transaction.") from exc
        if (receipt.get("request_id") != document["request_id"] or
                receipt.get("event_type") != document["event_type"] or
                not receipt.get("fabric_transaction_id")):
            raise FabricUnavailable("Fabric returned an invalid transaction receipt.")
        return receipt


def append_signed_event(db: Session, key: AuthorityKey, signature: bytes, settings: Settings,
                        request_id: UUID, event_type: str, object_ids: dict, payload: dict,
                        purpose: str, client: FabricClient | None = None) -> FabricTransaction:
    lock_ledger(db)
    if key.purpose != purpose or key.revoked_at is not None:
        raise ValueError(f"{purpose.title()} signing key is inactive.")

    action = action_document(request_id, event_type, object_ids, payload, key.user_id, key.id)
    try:
        Ed25519PublicKey.from_public_bytes(key.public_key).verify(signature, rfc8785.dumps(action))
    except (InvalidSignature, ValueError):
        raise ValueError("The event signature is invalid.") from None

    existing = db.get(FabricTransaction, request_id)
    if existing is not None:
        if (existing.event_type != event_type or existing.object_ids != object_ids or
                existing.payload != payload or existing.actor_id != key.user_id or
                existing.actor_key_id != key.id or existing.signature != signature):
            raise ValueError("The Fabric request ID is already bound to another event.")
        return existing

    receipt = (client or FabricClient(settings)).submit(action | {
        "signature": base64.b64encode(signature).decode("ascii"),
    })
    transaction = FabricTransaction(
        request_id=request_id,
        source="fabric",
        sequence=int(receipt["sequence"]),
        fabric_transaction_id=receipt["fabric_transaction_id"],
        submitted_transaction_id=receipt.get("submitted_transaction_id"),
        channel_name=settings.fabric_channel_name,
        chaincode_name=settings.fabric_chaincode_name,
        block_number=int(receipt["block_number"]),
        validation_code=int(receipt["validation_code"]),
        schema_version=int(receipt.get("schema_version", 1)),
        event_type=event_type,
        object_ids=object_ids,
        payload=payload,
        actor_id=key.user_id,
        actor_key_id=key.id,
        signature=signature,
        committed_at=datetime.fromisoformat(receipt["committed_at"].replace("Z", "+00:00")),
    )
    db.add(transaction)
    db.flush()
    return transaction


def append_admin_event(db: Session, key: AuthorityKey, private_key: Ed25519PrivateKey,
                       settings: Settings, request_id: UUID, event_type: str,
                       object_ids: dict, payload: dict,
                       client: FabricClient | None = None) -> FabricTransaction:
    if private_key.public_key().public_bytes_raw() != key.public_key:
        raise ValueError("Configured signing key does not match the registered key.")
    action = action_document(request_id, event_type, object_ids, payload, key.user_id, key.id)
    return append_signed_event(
        db, key, private_key.sign(rfc8785.dumps(action)), settings,
        request_id, event_type, object_ids, payload, "administrator", client,
    )
