# tests/test_capture.py
"""POST /api/capture/frames 엔드포인트의 저장 동작을 더미 이미지로 검증하는 테스트."""

from fastapi import FastAPI
from fastapi.testclient import TestClient

from backend.api.capture import router as capture_router

app = FastAPI()
app.include_router(capture_router)
client = TestClient(app)

DUMMY_JPEG = b"\xff\xd8\xff\xe0fake-jpeg-bytes"


def test_receive_capture_frames_saves_files(tmp_path, monkeypatch):
    """더미 대표 컷 1장 + 후보 프레임 2장을 업로드했을 때 응답 형식과 저장된 파일 내용이 기대값과 일치하는지 확인한다."""
    monkeypatch.chdir(tmp_path)

    response = client.post(
        "/api/capture/frames",
        data={"session_id": "test-session"},
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
    }

    session_dir = tmp_path / "captures" / "test-session"
    assert (session_dir / "representative.jpg").read_bytes() == DUMMY_JPEG
    assert (session_dir / "candidate_0.jpg").read_bytes() == DUMMY_JPEG
    assert (session_dir / "candidate_1.jpg").read_bytes() == DUMMY_JPEG
