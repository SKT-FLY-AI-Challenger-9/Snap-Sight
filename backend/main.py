# backend/main.py
"""FastAPI 앱 엔트리포인트. 앱 인스턴스를 생성하고 도메인별 라우터를 등록한다."""

from fastapi import FastAPI

from backend.api.capture import router as capture_router
from backend.config import validate_required_env
from backend.utils.logger import load_logger

logger = load_logger("main.log")

validate_required_env()

app = FastAPI(title="Snap-Sight Backend")

# capture_router가 /api/capture/frames 전체 경로를 라우터 내부에 이미 지정하므로 prefix를 주지 않는다.
app.include_router(capture_router)

logger.info("Snap-Sight 백엔드 앱을 초기화했습니다.")
