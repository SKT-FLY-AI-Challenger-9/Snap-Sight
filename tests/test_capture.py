# tests/test_capture.py
"""POST /api/capture/frames, GET /api/capture/{session_id}/result 엔드포인트를 확인하는 테스트."""

from io import BytesIO

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from PIL import Image

from backend.api.capture import router as capture_router
from backend.config import RESULT_POLL_INTERVAL_SECONDS
from backend.mllm.prompts import FrameComparisonResult
from backend.storage.frame_buffer import save_representative_frame

app = FastAPI()
app.include_router(capture_router)
client = TestClient(app)


def _make_jpeg() -> bytes:
    output = BytesIO()
    Image.new("RGB", (2, 2), "white").save(output, format="JPEG")
    return output.getvalue()


DUMMY_JPEG = _make_jpeg()


@pytest.fixture(autouse=True)
def _stub_capture_pipeline(monkeypatch):
    monkeypatch.setattr("backend.api.capture.trigger_capture_pipeline", lambda *args: None)


def test_receive_capture_frames_saves_files_and_triggers_comparison(tmp_path, monkeypatch):
    """더미 대표 컷 1장 + 후보 프레임 2장을 업로드하면 저장되고 MLLM 비교가 트리거된다."""
    monkeypatch.chdir(tmp_path)
    calls = []
    monkeypatch.setattr(
        "backend.api.capture.trigger_capture_pipeline",
        lambda *args: calls.append(args),
    )

    response = client.post(
        "/api/capture/frames",
        data={"session_id": "test-session", "raw_text": "인물 사진 찍어줘"},
        files=[
            ("representative_frame", ("rep.jpg", DUMMY_JPEG, "image/jpeg")),
            ("candidate_frames", ("cand0.jpg", DUMMY_JPEG, "image/jpeg")),
            ("candidate_frames", ("cand1.jpg", DUMMY_JPEG, "image/jpeg")),
        ],
    )

    assert response.status_code == 200
    assert response.json() == {
        "session_id": "test-session",
        "received_candidate_count": 2,
        "status": "saved",
        "capture_revision": 1,
        "final_frame_id": None,
    }

    session_dir = tmp_path / "captures" / "test-session"
    assert (session_dir / "representative.jpg").read_bytes() == DUMMY_JPEG
    assert (session_dir / "candidate_0.jpg").read_bytes() == DUMMY_JPEG
    assert (session_dir / "candidate_1.jpg").read_bytes() == DUMMY_JPEG
    assert len(calls) == 1
    assert calls[0][0] == "test-session"
    assert calls[0][1] == 1
    assert calls[0][2] == "인물 사진 찍어줘"
    assert calls[0][3] == []


def test_receive_capture_frames_finishes_pipeline_when_no_candidates(tmp_path, monkeypatch):
    """후보가 0장이어도 대표 프레임 설명을 위해 파이프라인을 트리거한다."""
    monkeypatch.chdir(tmp_path)
    calls = []
    monkeypatch.setattr(
        "backend.api.capture.trigger_capture_pipeline",
        lambda *args: calls.append(args),
    )

    response = client.post(
        "/api/capture/frames",
        data={"session_id": "test-session-empty", "raw_text": "인물 사진 찍어줘"},
        files=[("representative_frame", ("rep.jpg", DUMMY_JPEG, "image/jpeg"))],
    )

    assert response.status_code == 200
    assert response.json()["received_candidate_count"] == 0
    assert len(calls) == 1
    assert calls[0][0] == "test-session-empty"
    assert calls[0][3] == []


def test_receive_capture_frames_passes_parsed_candidate_scores(tmp_path, monkeypatch):
    """candidate_scores로 넘긴 JSON 문자열이 파싱되어 트리거 함수로 전달된다."""
    monkeypatch.chdir(tmp_path)
    calls = []
    monkeypatch.setattr(
        "backend.api.capture.trigger_capture_pipeline",
        lambda *args: calls.append(args[3]),
    )

    response = client.post(
        "/api/capture/frames",
        data={
            "session_id": "test-session-scores",
            "raw_text": "인물 사진 찍어줘",
            "candidate_scores": '[{"eyes_closed_score": 0.1}]',
        },
        files=[
            ("representative_frame", ("rep.jpg", DUMMY_JPEG, "image/jpeg")),
            ("candidate_frames", ("cand0.jpg", DUMMY_JPEG, "image/jpeg")),
        ],
    )

    assert response.status_code == 200
    assert calls == [[{"eyes_closed_score": 0.1}]]


def test_receive_capture_frames_rejects_malformed_candidate_scores(tmp_path, monkeypatch):
    """candidate_scores가 올바른 JSON 배열이 아니면 422로 명확히 실패한다."""
    monkeypatch.chdir(tmp_path)

    response = client.post(
        "/api/capture/frames",
        data={
            "session_id": "test-session-bad-scores",
            "raw_text": "인물 사진 찍어줘",
            "candidate_scores": "not-json",
        },
        files=[
            ("representative_frame", ("rep.jpg", DUMMY_JPEG, "image/jpeg")),
            ("candidate_frames", ("cand0.jpg", DUMMY_JPEG, "image/jpeg")),
        ],
    )

    assert response.status_code == 422


def test_receive_capture_frames_rejects_candidate_scores_count_mismatch(tmp_path, monkeypatch):
    """candidate_scores 개수가 후보 프레임 수와 다르면 422로 막는다 (점수-후보 오정렬 방지)."""
    monkeypatch.chdir(tmp_path)

    response = client.post(
        "/api/capture/frames",
        data={
            "session_id": "test-session-count-mismatch",
            "raw_text": "인물 사진 찍어줘",
            "candidate_scores": '[{"eyes_closed_score": 0.1}]',
        },
        files=[
            ("representative_frame", ("rep.jpg", DUMMY_JPEG, "image/jpeg")),
            ("candidate_frames", ("cand0.jpg", DUMMY_JPEG, "image/jpeg")),
            ("candidate_frames", ("cand1.jpg", DUMMY_JPEG, "image/jpeg")),
        ],
    )

    assert response.status_code == 422


def test_receive_capture_frames_saves_nothing_when_candidate_scores_invalid(tmp_path, monkeypatch):
    """candidate_scores 검증에 실패하면 프레임을 디스크에 남기지 않는다."""
    monkeypatch.chdir(tmp_path)

    response = client.post(
        "/api/capture/frames",
        data={
            "session_id": "test-session-no-partial-save",
            "raw_text": "인물 사진 찍어줘",
            "candidate_scores": "not-json",
        },
        files=[
            ("representative_frame", ("rep.jpg", DUMMY_JPEG, "image/jpeg")),
            ("candidate_frames", ("cand0.jpg", DUMMY_JPEG, "image/jpeg")),
        ],
    )

    assert response.status_code == 422
    assert not (tmp_path / "captures" / "test-session-no-partial-save").exists()


def test_receive_capture_frames_accepts_empty_raw_text(tmp_path, monkeypatch):
    """발화가 없는 세션(마이크 미허용·STT 실패)도 업로드가 성공해야 한다.

    FastAPI는 빈 폼 값을 '필드 누락'으로 처리하므로, raw_text가 필수면 rawText=""를
    보내는 앱의 업로드가 전부 422로 거부된다.
    """
    monkeypatch.chdir(tmp_path)
    calls = []
    monkeypatch.setattr(
        "backend.api.capture.trigger_capture_pipeline",
        lambda *args: calls.append(args[2]),
    )

    response = client.post(
        "/api/capture/frames",
        data={"session_id": "session-no-utterance", "raw_text": ""},
        files=[
            ("representative_frame", ("rep.jpg", DUMMY_JPEG, "image/jpeg")),
            ("candidate_frames", ("cand0.jpg", DUMMY_JPEG, "image/jpeg")),
        ],
    )

    assert response.status_code == 200
    assert (tmp_path / "captures" / "session-no-utterance" / "representative.jpg").exists()
    # 발화가 없어도 MLLM 비교는 진행한다 — 요구사항 없이 범용 결함 기준으로 판정하면 된다.
    assert calls == [""]


def test_get_capture_result_returns_pending_while_comparison_runs(tmp_path, monkeypatch):
    """업로드는 됐지만 비교가 안 끝났으면 pending과 재조회 간격을 반환한다."""
    monkeypatch.chdir(tmp_path)
    save_representative_frame("session-running", "rep.jpg", DUMMY_JPEG)

    response = client.get("/api/capture/session-running/result")

    assert response.status_code == 200
    assert response.json() == {
        "status": "pending",
        "improved": None,
        "reason": None,
        "retry_after_seconds": RESULT_POLL_INTERVAL_SECONDS,
        "capture_revision": None,
        "final_frame_id": None,
    }


def test_get_capture_result_returns_404_for_unknown_session(tmp_path, monkeypatch):
    """업로드된 적 없는 세션은 404로 끊는다 — pending이면 앱이 영원히 폴링하게 된다."""
    monkeypatch.chdir(tmp_path)

    response = client.get("/api/capture/no-such-session/result")

    assert response.status_code == 404


def test_get_capture_result_returns_done_when_ready(tmp_path, monkeypatch):
    """비교 결과가 저장돼 있으면 status=done과 함께 결과를 반환한다."""
    monkeypatch.chdir(tmp_path)
    from backend.storage.comparison_result import save_comparison_result

    save_comparison_result(
        "session-ready",
        FrameComparisonResult(improved=True, selected_frame="candidate_1", reason="더 낫습니다"),
    )

    response = client.get("/api/capture/session-ready/result")

    assert response.status_code == 200
    assert response.json() == {
        "status": "done",
        "improved": True,
        "reason": "더 낫습니다",
        "retry_after_seconds": None,
        "capture_revision": None,
        "final_frame_id": None,
    }
