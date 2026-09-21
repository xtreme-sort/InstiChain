# InstiChain

Faculty-authorized campus competition records and achievement verification.

The project provides a React + TypeScript frontend, a FastAPI backend,
configuration templates, a live API connection check and PostgreSQL models with
Alembic migrations. Login, competition workflows, signatures and Drive integration
come in later milestones.
The health endpoint reports API availability only.

## Requirements

- Node.js 22.12 or newer and npm
- Python 3.12 or newer with `pip` and `venv`
- PostgreSQL 16 or newer, or Docker with Compose for the local database

Cloud credentials are not required yet. The frontend and API health endpoint
still start without a database; migrations require PostgreSQL.

## Project Structure

```text
backend/
  app/
    config.py          Environment settings
    database.py        SQLAlchemy base, engine and session dependency
    main.py            FastAPI app and GET /api/health
    models.py          Users, clubs, appointments, competitions, credentials, ledger
  migrations/          Versioned Alembic migrations
  tests/               PostgreSQL migration and constraint tests
  alembic.ini          Migration configuration
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

## Database Setup

From the repository root, start the local PostgreSQL service:

```bash
docker compose up -d --wait db
```

The local-only database uses database/user/password `instichain` on
`127.0.0.1:5432`. Its data persists in the Compose volume. With an existing
PostgreSQL installation, create a database and set `INSTICHAIN_DATABASE_URL` in
`backend/.env` to its connection URL instead. If port 5432 is occupied, use the
existing database or change the Compose host port and the connection URL together.

From the repository root, apply the migration:

```bash
cd backend
source .venv/bin/activate
python -m pip install -r requirements.txt
python -m alembic upgrade head
python -m alembic current
```

For an existing checkout, add `INSTICHAIN_DATABASE_URL` from `.env.example` to
your existing `.env`; do not overwrite your other settings. The default URL also
matches the Compose database. Revision `0001` creates the six core tables.
Migrations run explicitly; starting FastAPI never creates or changes tables.

From `backend/`, inspect generated SQL without connecting to PostgreSQL:

```bash
python -m alembic upgrade head --sql
```

For future schema changes, edit the models, generate a revision, review it, then
apply it:

```bash
python -m alembic revision --autogenerate -m "describe schema change"
python -m alembic upgrade head
python -m alembic check
```

`python -m alembic downgrade base` drops these tables and their data. Use it only
on disposable databases when checking rollback. Stop the local database from the
repository root with `docker compose stop db`; this preserves its volume.

See [database schema notes](backend/SCHEMA.md) for relationships and the boundary
between database constraints and future authorization/signature validation.

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
| `backend/.env` | `INSTICHAIN_DATABASE_URL` | `postgresql+psycopg://instichain:instichain@127.0.0.1:5432/instichain` | PostgreSQL connection used by migrations and database sessions |
| `frontend/.env` | `API_PROXY_TARGET` | `http://127.0.0.1:8000` | Backend destination for the Vite development proxy |

Both apps work with defaults when `.env` is absent. Restart the relevant server
after configuration changes. Vite proxies `/api` requests to the backend during
development, so the browser uses the same origin and no CORS configuration is
needed. Never put secrets into frontend `VITE_` variables: they are public.

If a port is occupied, run the backend with `--port 8001` and update
`API_PROXY_TARGET`, or run the frontend with `npm run dev -- --port 5174`.

## Verification and Build

From `backend/`, run database integration tests against a local test database:

```bash
INSTICHAIN_TEST_DATABASE_URL=postgresql+psycopg://instichain:instichain@127.0.0.1:5432/instichain python -m unittest discover -s tests -v
```

Tests create a randomly named schema, apply migrations, check constraints and
rollback/reapply behavior, then remove only their own schema. The test database
user needs permission to create schemas. Without the test URL, database tests
are explicitly skipped. Use a local development database, not production.

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
- [Alembic migrations](https://alembic.sqlalchemy.org/en/latest/tutorial.html)
- [SQLAlchemy declarative models](https://docs.sqlalchemy.org/en/20/orm/declarative_tables.html)
