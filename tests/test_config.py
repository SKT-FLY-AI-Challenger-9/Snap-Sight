# tests/test_config.py
"""backend.config의 환경변수 로드·검증 로직을 확인하는 테스트."""

import pytest

from backend.config import load_env_variable, validate_required_env


def test_load_env_variable_returns_value_when_set(monkeypatch):
    """환경변수가 설정되어 있으면 해당 값을 그대로 반환한다."""
    monkeypatch.setenv("ANTHROPIC_API_KEY", "dummy-key")
    assert load_env_variable("ANTHROPIC_API_KEY") == "dummy-key"


def test_load_env_variable_raises_when_missing(monkeypatch):
    """환경변수가 없으면 하드코딩된 기본값 없이 명확한 RuntimeError를 발생시킨다."""
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    with pytest.raises(RuntimeError, match="ANTHROPIC_API_KEY"):
        load_env_variable("ANTHROPIC_API_KEY")


def test_validate_required_env_raises_when_missing(monkeypatch):
    """필수 환경변수가 하나라도 없으면 부팅 검증 함수가 명확히 실패한다."""
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    with pytest.raises(RuntimeError):
        validate_required_env()
