# tests/test_metadata.py
"""검색용 상세 메타데이터(backend/mllm/metadata.py)와 조회 API 테스트 (기능 3-B)."""

import json

from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai.photo_labels import default_photo_labels
from backend.api.capture import router as capture_router
from backend.mllm.metadata import (
    PhotoMetadataOutput,
    build_user_prompt,
    load_metadata,
    save_metadata,
)
from backend.storage.frame_buffer import save_representative_frame

app = FastAPI()
app.include_router(capture_router)
client = TestClient(app)

DUMMY_JPEG = b"\xff\xd8\xff\xe0fake-jpeg-bytes"


# --- save/load: 폐쇄형 계약 강제 ---


def test_save_metadata_drops_labels_outside_the_dictionary(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    taxonomy = default_photo_labels()
    known = next(iter(taxonomy.ids()))
    result = PhotoMetadataOutput(
        long_description="따뜻한 조명 아래 케이크가 놓여 있어요.",
        labels=[known, "invented-by-llm"],
        custom_labels=["제주도 여행", "LLM이 지어낸 라벨"],
        people_count=2,
    )

    save_metadata("s_test", result, taxonomy, requested_custom_labels=["제주도 여행"])
    payload = load_metadata("s_test")

    assert payload["labels"] == [known]  # 사전 밖 라벨은 버려진다
    assert payload["custom_labels"] == ["제주도 여행"]  # 요청 목록에 없던 이름은 버려진다
    assert payload["people_count"] == 2
    assert payload["taxonomy_version"] == taxonomy.version


def test_save_metadata_failure_writes_null_payload(tmp_path, monkeypatch):
    """실패해도 파일은 저장된다 — 파일 존재가 '생성 시도 완료' 신호 (폴링 종료 규약)."""
    monkeypatch.chdir(tmp_path)
    taxonomy = default_photo_labels()
    save_metadata("s_fail", None, taxonomy, requested_custom_labels=[])
    payload = load_metadata("s_fail")
    assert payload["long_description"] is None
    assert payload["labels"] == []


def test_load_metadata_returns_none_before_generation(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    assert load_metadata("s_missing") is None


# --- 프롬프트 구성 ---


def test_prompt_includes_catalog_custom_labels_and_context():
    taxonomy = default_photo_labels()
    prompt = build_user_prompt(
        raw_text="인물 사진 찍어줘",
        custom_labels=["제주도 여행"],
        detected_objects=["person", "cake"],
        taxonomy=taxonomy,
    )
    assert taxonomy.labels[0].id in prompt
    assert "제주도 여행" in prompt
    assert "인물 사진 찍어줘" in prompt
    assert "person, cake" in prompt


def test_prompt_marks_missing_context_explicitly():
    prompt = build_user_prompt(
        raw_text="", custom_labels=[], detected_objects=[], taxonomy=default_photo_labels()
    )
    assert "(없음)" in prompt


# --- 업로드 → 트리거 배선 ---


def test_upload_triggers_metadata_with_parsed_fields(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr("backend.api.capture.trigger_comparison", lambda *args: None)
    monkeypatch.setattr("backend.api.capture.trigger_description", lambda *args: None)
    calls = []
    monkeypatch.setattr(
        "backend.api.capture.trigger_metadata",
        lambda session_id, raw_text, custom_labels, detected_objects: calls.append(
            (session_id, raw_text, custom_labels, detected_objects)
        ),
    )

    response = client.post(
        "/api/capture/frames",
        data={
            "session_id": "s_meta",
            "raw_text": "케이크 찍어줘",
            "custom_labels": json.dumps(["제주도 여행"], ensure_ascii=False),
            "detected_objects": json.dumps(["cake"]),
        },
        files=[("representative_frame", ("rep.jpg", DUMMY_JPEG, "image/jpeg"))],
    )

    assert response.status_code == 200
    assert calls == [("s_meta", "케이크 찍어줘", ["제주도 여행"], ["cake"])]


def test_upload_rejects_malformed_custom_labels(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    response = client.post(
        "/api/capture/frames",
        data={"session_id": "s_bad", "custom_labels": "not-json"},
        files=[("representative_frame", ("rep.jpg", DUMMY_JPEG, "image/jpeg"))],
    )
    assert response.status_code == 422


# --- 조회 API 규약 (description/result 와 동일) ---


def test_metadata_endpoint_returns_pending_then_done(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    save_representative_frame("s_poll", "rep.jpg", DUMMY_JPEG)

    pending = client.get("/api/capture/s_poll/metadata")
    assert pending.status_code == 200
    assert pending.json()["status"] == "pending"
    assert pending.json()["retry_after_seconds"] is not None

    taxonomy = default_photo_labels()
    known = next(iter(taxonomy.ids()))
    save_metadata(
        "s_poll",
        PhotoMetadataOutput(long_description="설명입니다.", labels=[known]),
        taxonomy,
        requested_custom_labels=[],
    )

    done = client.get("/api/capture/s_poll/metadata")
    assert done.status_code == 200
    body = done.json()
    assert body["status"] == "done"
    assert body["long_description"] == "설명입니다."
    assert body["labels"] == [known]


def test_metadata_endpoint_unknown_session_is_404(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    assert client.get("/api/capture/s_nope/metadata").status_code == 404
