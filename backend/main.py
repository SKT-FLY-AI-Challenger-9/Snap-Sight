# backend/main.py
"""FastAPI 앱 엔트리포인트. 앱 인스턴스를 생성하고 도메인별 라우터를 등록한다."""

from fastapi import FastAPI

from backend.api.capture import router as capture_router
from backend.api.tts import router as tts_router
from backend.config import validate_required_env
from backend.utils.logger import load_logger

logger = load_logger("main.log")

validate_required_env()

app = FastAPI(title="Snap-Sight Backend")

# 각 라우터가 전체 경로를 내부에 이미 지정하므로 prefix를 주지 않는다.
app.include_router(capture_router)
app.include_router(tts_router)

logger.info("Snap-Sight 백엔드 앱을 초기화했습니다.")


def run() -> None:
    """설정된 host·port로 개발 서버를 띄운다. 실기기 접속을 위해 기본 바인딩은 0.0.0.0이다."""
    import uvicorn

    host = load_server_host()
    port = load_server_port()
    logger.info(f"개발 서버를 {host}:{port}에서 시작합니다.")
    uvicorn.run(app, host=host, port=port)


if __name__ == "__main__":
    run()
