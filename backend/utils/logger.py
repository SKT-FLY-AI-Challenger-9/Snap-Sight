# backend/utils/logger.py
"""표준 포맷(시간·레벨·모듈명)의 콘솔 로거를 생성한다. print() 대신 모든 백엔드 모듈에서 공용으로 사용."""

import logging


def load_logger(name: str) -> logging.Logger:
    """이름 기준으로 로거를 가져오거나 새로 만들어 반환한다. 핸들러 중복 등록을 방지한다."""
    logger = logging.getLogger(name)
    if not logger.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter("%(asctime)s [%(levelname)s] %(name)s: %(message)s"))
        logger.addHandler(handler)
        logger.setLevel(logging.INFO)
        logger.propagate = False
    return logger
