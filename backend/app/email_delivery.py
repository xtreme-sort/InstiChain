import smtplib
import ssl
from email.message import EmailMessage

from app.config import Settings


def send_verification_email(email: str, token: str, settings: Settings) -> None:
    url = f"{str(settings.public_app_url).rstrip('/')}/verify-email#token={token}"
    message = EmailMessage()
    message["Subject"] = "Verify your InstiChain email"
    message["From"] = settings.smtp_from
    message["To"] = email
    message.set_content(
        f"Confirm your institute email address:\n\n{url}\n\n"
        f"This link expires in {settings.verification_ttl_minutes} minutes and works once.\n"
        "If you did not request this email, you can ignore it.\n"
    )
    client = smtplib.SMTP_SSL if settings.smtp_security == "ssl" else smtplib.SMTP
    kwargs = {"context": ssl.create_default_context()} if settings.smtp_security == "ssl" else {}
    with client(settings.smtp_host, settings.smtp_port, timeout=10, **kwargs) as smtp:
        if settings.smtp_security == "starttls":
            smtp.starttls(context=ssl.create_default_context())
        if settings.smtp_username:
            smtp.login(settings.smtp_username, settings.smtp_password.get_secret_value() if settings.smtp_password else "")
        smtp.send_message(message)
