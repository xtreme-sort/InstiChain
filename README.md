# InstiChain

Faculty-authorized campus competition records and achievement verification.

This milestone provides a React + TypeScript frontend, a FastAPI backend,
configuration templates and a live API connection check. Accounts, PostgreSQL,
competition workflows, signatures and Drive integration come in later milestones.
The health endpoint reports API availability only.

## Requirements

- Node.js 22.12 or newer and npm
- Python 3.12 or newer with `pip` and `venv`

PostgreSQL and cloud credentials are not required yet.

## Project Structure

```text
backend/
  app/
    config.py          Environment settings
    main.py            FastAPI app and GET /api/health
  .env.example         Backend configuration template
  requirements.txt     Python dependencies
frontend/
  src/                 React app and styles
  .env.example         Development API proxy configuration
  package.json         Frontend scripts and dependencies
  package-lock.json    Frontend dependency lockfile
  vite.config.ts       Development server and API proxy
```

## Backend Setup

From the repository root, in the first terminal:

```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
cp .env.example .env
python -m uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
```

On Windows, activate with `.venv\Scripts\Activate.ps1` in PowerShell and use
`Copy-Item .env.example .env`. Use `python` if `python3` is unavailable.

- Health: <http://127.0.0.1:8000/api/health>
- Interactive API documentation: <http://127.0.0.1:8000/docs>

The health response is `{"status":"ok"}`.

## Frontend Setup

From the repository root, in a second terminal:

```bash
cd frontend
npm ci
cp .env.example .env
npm run dev
```

Open <http://127.0.0.1:5173>. The service status should become **Connected**.
Use the refresh button to check again after restarting the backend.
Stop either development server with `Ctrl+C` in its terminal.

## Configuration

| File | Variable | Default | Purpose |
| --- | --- | --- | --- |
| `backend/.env` | `INSTICHAIN_APP_NAME` | `InstiChain API` | API documentation title |
| `frontend/.env` | `API_PROXY_TARGET` | `http://127.0.0.1:8000` | Backend destination for the Vite development proxy |

Both apps work with defaults when `.env` is absent. Restart the relevant server
after configuration changes. Vite proxies `/api` requests to the backend during
development, so the browser uses the same origin and no CORS configuration is
needed. Never put secrets into frontend `VITE_` variables: they are public.

If a port is occupied, run the backend with `--port 8001` and update
`API_PROXY_TARGET`, or run the frontend with `npm run dev -- --port 5174`.

## Verification and Build

With both servers running:

```bash
curl --fail http://127.0.0.1:8000/api/health
curl --fail http://127.0.0.1:5173/api/health
```

From `frontend/`:

```bash
npm run typecheck
npm run build
```

The build writes assets to `frontend/dist/`. `npm run preview` previews static
assets only; it does not provide the development API proxy. A deployment must
serve assets and route `/api` to FastAPI on the same origin. These local startup
commands are not a production deployment configuration.

## Git

Commit source files, `.env.example` templates and `frontend/package-lock.json`.
Local `.env` files, dependencies, build output and `IMPLEMENTATION_PLAN.md` are
ignored. No credentials are needed or included in this scaffold.

## Framework References

- [Vite guide](https://vite.dev/guide/)
- [FastAPI first steps](https://fastapi.tiangolo.com/tutorial/first-steps/)
- [Pydantic settings](https://docs.pydantic.dev/latest/concepts/pydantic_settings/)
