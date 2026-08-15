# tests/test_mllm_client.py
"""backend.mllm.client의 MLLM API 클라이언트 로직을 mock으로 검증하는 테스트 (실제 네트워크 호출 없음)."""

import httpx
import pytest
from anthropic import APIConnectionError, APIStatusError

from backend.mllm.client import compare_candidate_frames
from backend.mllm.prompts import FrameComparisonResult
from backend.storage.frame_buffer import (
    load_session_frame_paths,
    save_candidate_frame,
    save_representative_frame,
)

DUMMY_REQUEST = httpx.Request("POST", "https://api.anthropic.com/v1/messages")


class _FakeResponse:
    """Anthropic 클라이언트가 반환하는 messages.parse() 응답을 흉내낸다."""

    def __init__(self, parsed_output):
        self.parsed_output = parsed_output


class _FakeMessages:
    """Anthropic 클라이언트의 client.messages 속성을 흉내낸다."""

    def __init__(self, response=None, error=None):
        self._response = response
        self._error = error

    def parse(self, **kwargs):
        if self._error is not None:
            raise self._error
        return self._response


class _FakeAnthropicClient:
    """실제 네트워크 호출 없이 Anthropic() 생성자를 대체하는 가짜 클라이언트."""

    def __init__(self, response=None, error=None):
        self.messages = _FakeMessages(response=response, error=error)


@pytest.fixture
def dummy_frames(tmp_path):
    """대표 컷 1장과 후보 프레임 1장의 더미 이미지 파일 경로를 만들어 반환한다."""
    representative = tmp_path / "representative.jpg"
    representative.write_bytes(b"fake-jpeg-bytes")
    candidate = tmp_path / "candidate_0.jpg"
    candidate.write_bytes(b"fake-jpeg-bytes")
    return representative, [candidate]


def test_compare_candidate_frames_returns_parsed_result_on_success(monkeypatch, dummy_frames):
    """정상 응답이 오면 파싱된 FrameComparisonResult를 그대로 반환한다."""
    representative, candidates = dummy_frames
    expected = FrameComparisonResult(
        improved=True, selected_frame="candidate_1", reason="더 나은 표정입니다"
    )
    monkeypatch.setattr(
        "backend.mllm.client.Anthropic",
        lambda: _FakeAnthropicClient(response=_FakeResponse(expected)),
    )

    result = compare_candidate_frames("인물 사진 찍어줘", {}, representative, candidates)

    assert result == expected


def test_compare_candidate_frames_falls_back_when_api_connection_fails(monkeypatch, dummy_frames):
    """API 연결 실패 시 예외를 밖으로 던지지 않고 대표 컷 유지 결과로 fallback한다."""
    representative, candidates = dummy_frames
    error = APIConnectionError(request=DUMMY_REQUEST)
    monkeypatch.setattr(
        "backend.mllm.client.Anthropic",
        lambda: _FakeAnthropicClient(error=error),
    )

    result = compare_candidate_frames("인물 사진 찍어줘", {}, representative, candidates)

    assert result.improved is False
    assert result.selected_frame is None


def test_compare_candidate_frames_falls_back_when_api_returns_error_status(
    monkeypatch, dummy_frames
):
    """API가 에러 상태 코드를 반환해도 대표 컷 유지 결과로 안전하게 fallback한다."""
    representative, candidates = dummy_frames
    response = httpx.Response(500, request=DUMMY_REQUEST)
    error = APIStatusError("서버 오류", response=response, body=None)
    monkeypatch.setattr(
        "backend.mllm.client.Anthropic",
        lambda: _FakeAnthropicClient(error=error),
    )

    result = compare_candidate_frames("인물 사진 찍어줘", {}, representative, candidates)

    assert result.improved is False
    assert result.selected_frame is None


def test_compare_candidate_frames_falls_back_when_response_fails_to_parse(
    monkeypatch, dummy_frames
):
    """응답이 스키마와 일치하지 않아 parsed_output이 비어있으면 대표 컷 유지로 fallback한다."""
    representative, candidates = dummy_frames
    monkeypatch.setattr(
        "backend.mllm.client.Anthropic",
        lambda: _FakeAnthropicClient(response=_FakeResponse(None)),
    )

    result = compare_candidate_frames("인물 사진 찍어줘", {}, representative, candidates)

    assert result.improved is False
    assert result.selected_frame is None


def test_compare_candidate_frames_accepts_paths_loaded_from_saved_session(monkeypatch, tmp_path):
    """#6이 저장한 프레임을 #1의 조회 함수로 읽어 그대로 MLLM 클라이언트에 넘길 수 있다."""
    monkeypatch.chdir(tmp_path)
    save_representative_frame("session-mllm", "rep.jpg", b"fake-jpeg-bytes")
    save_candidate_frame("session-mllm", 0, "cand0.jpg", b"fake-jpeg-bytes")
    representative, candidates = load_session_frame_paths("session-mllm")

    expected = FrameComparisonResult(improved=False, selected_frame=None, reason="개선 없음")
    monkeypatch.setattr(
        "backend.mllm.client.Anthropic",
        lambda: _FakeAnthropicClient(response=_FakeResponse(expected)),
    )

    result = compare_candidate_frames("인물 사진 찍어줘", {}, representative, candidates)

    assert result == expected
