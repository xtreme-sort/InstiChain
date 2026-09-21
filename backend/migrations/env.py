from alembic import context
from sqlalchemy import create_engine, pool

from app.config import Settings
from app.database import Base
from app import models  # noqa: F401 - register all tables with Base.metadata

target_metadata = Base.metadata


def run_migrations() -> None:
    if context.is_offline_mode():
        context.configure(
            url=Settings().database_url,
            target_metadata=target_metadata,
            literal_binds=True,
            dialect_opts={"paramstyle": "named"},
        )
        with context.begin_transaction():
            context.run_migrations()
        return

    # Tests may supply a connection in an isolated PostgreSQL schema.
    connection = context.config.attributes.get("connection")
    if connection is not None:
        context.configure(connection=connection, target_metadata=target_metadata)
        with context.begin_transaction():
            context.run_migrations()
        return

    engine = create_engine(Settings().database_url, poolclass=pool.NullPool)
    with engine.connect() as connection:
        context.configure(connection=connection, target_metadata=target_metadata)
        with context.begin_transaction():
            context.run_migrations()
    engine.dispose()


run_migrations()
