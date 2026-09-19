from fastapi import FastAPI
from pydantic import BaseModel

from app.config import Settings

settings = Settings()
app = FastAPI(title=settings.app_name, version="0.1.0")


class HealthResponse(BaseModel):
    status: str


@app.get("/api/health", response_model=HealthResponse, tags=["health"])
def health() -> HealthResponse:
    return HealthResponse(status="ok")
