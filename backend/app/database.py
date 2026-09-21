from functools import lru_cache
from collections.abc import Generator

from sqlalchemy import MetaData, create_engine
from sqlalchemy.engine import Engine
from sqlalchemy.orm import DeclarativeBase, Session

from app.config import Settings


class Base(DeclarativeBase):
    metadata = MetaData(naming_convention={
        "ix": "ix_%(table_name)s_%(column_0_name)s",
        "uq": "uq_%(table_name)s_%(column_0_name)s",
        "ck": "ck_%(table_name)s_%(constraint_name)s",
        "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
        "pk": "pk_%(table_name)s",
    })


@lru_cache
def get_engine() -> Engine:
    return create_engine(Settings().database_url, pool_pre_ping=True)


def get_session() -> Generator[Session, None, None]:
    with Session(get_engine()) as session:
        yield session
