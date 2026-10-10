from fastapi import FastAPI
from pydantic import BaseModel

from app.config import Settings
from app.admin import router as admin_router
from app.advisor import router as advisor_router
from app.competitions import router as competitions_router
from app.ledger import router as ledger_router
from app.results import router as results_router
from app.session import router as session_router
from app.verification import router as verification_router

settings = Settings()
app = FastAPI(title=settings.app_name, version="0.1.0")
app.include_router(verification_router)
app.include_router(session_router)
app.include_router(admin_router)
app.include_router(advisor_router)
app.include_router(competitions_router)
app.include_router(results_router)
app.include_router(ledger_router)


class HealthResponse(BaseModel):
    status: str


@app.get("/api/health", response_model=HealthResponse, tags=["health"])
def health() -> HealthResponse:
    return HealthResponse(status="ok")
