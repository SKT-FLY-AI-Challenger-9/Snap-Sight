# backend/utils/logger.py
"""로그 파일명을 받아 콘솔·파일에 동시에 기록하는 로거를 생성한다. print() 대신 모든 백엔드 모듈에서 공용으로 사용."""

import logging
import os
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent


def load_logger(log_filename: str) -> logging.Logger:
    """로그 파일명(예: "main.log")을 받아 콘솔·파일 핸들러를 갖는 로거를 반환한다. 핸들러 중복 등록을 방지한다."""
    logger_name = log_filename.removesuffix(".log")
    logger = logging.getLogger(logger_name)
    logger.setLevel(logging.INFO)

    if logger.handlers:
        return logger

    log_dir = Path(os.environ.get("LOG_DIR", str(PROJECT_ROOT / "data/logs")))
    log_dir.mkdir(parents=True, exist_ok=True)

    fmt = logging.Formatter("[%(asctime)s] [%(levelname)s] %(message)s")

    stream_handler = logging.StreamHandler()
    stream_handler.setFormatter(fmt)
    logger.addHandler(stream_handler)

    file_handler = logging.FileHandler(log_dir / log_filename, encoding="utf-8")
    file_handler.setFormatter(fmt)
    logger.addHandler(file_handler)

    return logger
