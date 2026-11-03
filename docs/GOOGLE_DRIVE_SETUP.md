# Google Drive Test Setup

1. Create a Google Cloud OAuth **Web application** client with the Drive API enabled.
2. Add `http://127.0.0.1:8000/api/storage/google/callback` as an exact authorized redirect URI. The host, port, and path must match `INSTICHAIN_GOOGLE_REDIRECT_URI`.
3. If the consent screen is in Testing mode, add each Drive test account as a test user.
4. Generate a stable encryption key with `openssl rand -base64 32`. Set `INSTICHAIN_PROOF_ENCRYPTION_KEY`, `INSTICHAIN_GOOGLE_CLIENT_ID`, and `INSTICHAIN_GOOGLE_CLIENT_SECRET` in the ignored `backend/.env`. Do not commit credentials.
5. Start PostgreSQL, Mailpit, Fabric, the backend, and the frontend. Sign in to InstiChain before connecting Drive. The OAuth callback uses the same browser session and returns to the configured frontend origin.

The application requests only `https://www.googleapis.com/auth/drive.file` and offline access. That scope does **not** grant access to every file in a user's Drive; only app-created or explicitly app-opened files are available. Disconnect removes the local refresh credential even when Google is temporarily unavailable. Existing issued credentials and archived proofs remain intact.

The callback never places access or refresh tokens in the browser URL. Provider errors return a generic status; inspect server health and configuration rather than logging authorization codes or tokens.
