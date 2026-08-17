# tests/test_config.py
"""backend.config의 환경변수 로드·검증 로직을 확인하는 테스트."""

import pytest

from backend.config import (
    DEFAULT_SERVER_HOST,
    DEFAULT_SERVER_PORT,
    load_env_variable,
    load_server_host,
    load_server_port,
    validate_required_env,
)


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


def test_load_server_host_defaults_to_all_interfaces(monkeypatch):
    """기본 바인딩은 0.0.0.0이어야 실기기가 PC의 백엔드에 접속할 수 있다."""
    monkeypatch.delenv("SERVER_HOST", raising=False)
    assert load_server_host() == DEFAULT_SERVER_HOST == "0.0.0.0"


def test_load_server_host_can_be_overridden(monkeypatch):
    """SERVER_HOST로 바인딩 주소를 재정의할 수 있다."""
    monkeypatch.setenv("SERVER_HOST", "127.0.0.1")
    assert load_server_host() == "127.0.0.1"


def test_load_server_port_defaults_and_overrides(monkeypatch):
    """SERVER_PORT가 없으면 기본값을, 있으면 그 값을 정수로 반환한다."""
    monkeypatch.delenv("SERVER_PORT", raising=False)
    assert load_server_port() == DEFAULT_SERVER_PORT

    monkeypatch.setenv("SERVER_PORT", "9000")
    assert load_server_port() == 9000


def test_load_server_port_falls_back_when_not_a_number(monkeypatch):
    """SERVER_PORT가 정수가 아니면 서버를 죽이지 않고 기본값으로 되돌린다."""
    monkeypatch.setenv("SERVER_PORT", "팔천")
    assert load_server_port() == DEFAULT_SERVER_PORT
