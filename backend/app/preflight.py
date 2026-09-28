"""Early external integration checks; never prints credentials or response bodies."""

import argparse
import re
import smtplib
from email.message import EmailMessage

import httpx

from app.config import Settings
from app.email_delivery import deliver_message, smtp_connection
from app.verification import normalize_institute_email


def check_smtp(settings: Settings, recipient: str | None = None) -> str:
    if recipient:
        recipient = normalize_institute_email(recipient)
        message = EmailMessage()
        message["From"] = settings.smtp_from
        message["To"] = recipient
        message["Subject"] = "InstiChain delivery check"
        message.set_content("InstiChain SMTP delivery check. No account or permission changes were made.")
        deliver_message(message, settings)
        return "SMTP accepted the test message. Confirm receipt in the recipient inbox; acceptance alone is not delivery proof."
    with smtp_connection(settings) as smtp:
        code, _ = smtp.noop()
        if code != 250:
            raise ValueError("SMTP health command was rejected.")
    return "SMTP connection and configured authentication succeeded. No message was sent."


def check_drive(settings: Settings, client: httpx.Client | None = None) -> str:
    if not settings.drive_test_access_token:
        raise ValueError("Drive check not configured: set INSTICHAIN_DRIVE_TEST_ACCESS_TOKEN with drive.file authorization.")
    file_id = settings.drive_test_file_id
    if file_id and not re.fullmatch(r"[A-Za-z0-9_-]+", file_id):
        raise ValueError("Invalid Drive test file ID.")
    headers = {"Authorization": "Bearer " + settings.drive_test_access_token.get_secret_value()}

    def inspect(client: httpx.Client) -> str:
        response = client.get("https://www.googleapis.com/drive/v3/about", params={"fields": "user(permissionId)"}, headers=headers)
        if response.status_code != 200:
            raise ValueError(f"Drive account check failed (HTTP {response.status_code}); check token expiry, scope and API access.")
        if not response.json().get("user", {}).get("permissionId"):
            raise ValueError("Drive account response did not identify a user.")
        if file_id:
            response = client.get(f"https://www.googleapis.com/drive/v3/files/{file_id}",
                                  params={"fields": "id,trashed,capabilities(canDownload)", "supportsAllDrives": "true"}, headers=headers)
            if response.status_code != 200:
                raise ValueError(f"Drive file check failed (HTTP {response.status_code}); select a file explicitly shared with this OAuth app.")
            data = response.json()
            if data.get("id") != file_id or data.get("trashed") or not data.get("capabilities", {}).get("canDownload"):
                raise ValueError("Drive test file is unavailable or cannot be downloaded.")
            return "Drive account and selected-file metadata access confirmed. No file bytes were downloaded or modified."
        return "Drive account access confirmed. File access was not checked; set INSTICHAIN_DRIVE_TEST_FILE_ID."

    if client is not None:
        return inspect(client)
    with httpx.Client(timeout=15, follow_redirects=False) as owned_client:
        return inspect(owned_client)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    smtp = commands.add_parser("smtp")
    smtp.add_argument("--recipient", help="Explicitly send a test message to this institute address")
    commands.add_parser("drive")
    args = parser.parse_args()
    try:
        settings = Settings()
        print(check_smtp(settings, args.recipient) if args.command == "smtp" else check_drive(settings))
    except (httpx.HTTPError, OSError, smtplib.SMTPException):
        parser.exit(1, "Integration connection failed. Check network access and provider settings.\n")
    except ValueError as exc:
        parser.exit(1, f"{exc}\n")


if __name__ == "__main__":
    main()
