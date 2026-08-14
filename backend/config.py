# backend/config.py
"""저장소 루트의 .env를 로드하고, 앱 실행에 필요한 환경변수를 검증·제공한다."""

import os

from dotenv import load_dotenv

from backend.utils.logger import load_logger

logger = load_logger("config.log")

load_dotenv()

# 앱 부팅 시 반드시 있어야 하는 환경변수 목록.
# ANTHROPIC_API_KEY: MLLM(Claude) 연동용. anthropic SDK가 Anthropic() 호출 시 자동으로 읽는 표준 이름.
REQUIRED_ENV_VARS: tuple[str, ...] = ("ANTHROPIC_API_KEY",)


def load_env_variable(key: str) -> str:
    """환경변수 값을 조회한다. 값이 없으면 하드코딩 없이 명확한 예외를 발생시킨다."""
    value = os.getenv(key)
    if not value:
        raise RuntimeError(
            f"필수 환경변수 '{key}'가 설정되지 않았습니다. "
            f"저장소 루트에 .env 파일을 만들고 '{key}=값' 형태로 추가하세요."
        )
    return value


def validate_required_env() -> None:
    """REQUIRED_ENV_VARS에 정의된 필수 환경변수가 모두 설정되어 있는지 확인한다."""
    for key in REQUIRED_ENV_VARS:
        load_env_variable(key)
    logger.info("필수 환경변수 검증을 완료했습니다.")
