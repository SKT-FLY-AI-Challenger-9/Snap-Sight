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

# 실기기(⑤)는 PC의 127.0.0.1에 닿을 수 없으므로 기본 바인딩을 0.0.0.0으로 둔다.
# 개발용 평문 HTTP 전제이며, 신뢰할 수 있는 로컬 네트워크에서만 사용한다.
DEFAULT_SERVER_HOST = "0.0.0.0"
DEFAULT_SERVER_PORT = 8000

# MLLM 비교 소요 시간 실측치(#37 수동 검증): 규칙 기반 파싱 약 6초, LLM 폴백까지 타면 약 12초.
# 앱이 이보다 짧게 잡으면 폴백 세션이 전부 실패로 보이므로 여유를 둔 값을 권장한다.
RESULT_POLL_INTERVAL_SECONDS = 2
RESULT_POLL_TIMEOUT_SECONDS = 30


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


def load_server_host() -> str:
    """서버가 바인딩할 호스트를 반환한다. SERVER_HOST로 재정의할 수 있다."""
    return os.getenv("SERVER_HOST") or DEFAULT_SERVER_HOST


def load_server_port() -> int:
    """서버가 바인딩할 포트를 반환한다. SERVER_PORT가 숫자가 아니면 기본값으로 되돌린다."""
    raw_port = os.getenv("SERVER_PORT")
    if not raw_port:
        return DEFAULT_SERVER_PORT
    try:
        return int(raw_port)
    except ValueError:
        logger.error(
            f"SERVER_PORT '{raw_port}'가 정수가 아니라 기본값 {DEFAULT_SERVER_PORT}를 사용합니다."
        )
        return DEFAULT_SERVER_PORT
