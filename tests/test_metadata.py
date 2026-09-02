# tests/test_metadata.py
"""검색용 상세 메타데이터(backend/mllm/metadata.py)와 조회 API 테스트 (기능 3-B)."""

import json
from io import BytesIO

from fastapi import FastAPI
from fastapi.testclient import TestClient
from PIL import Image

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


def _make_jpeg() -> bytes:
    output = BytesIO()
    Image.new("RGB", (2, 2), "white").save(output, format="JPEG")
    return output.getvalue()


DUMMY_JPEG = _make_jpeg()


# --- save/load: 폐쇄형 계약 강제 ---


def test_save_metadata_drops_labels_outside_the_dictionary(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    taxonomy = default_photo_labels()
    known = next(iter(taxonomy.ids()))
    result = PhotoMetadataOutput(
        long_description="따뜻한 조명 아래 케이크가 놓여 있어요.",
        labels=[known, "invented-by-llm", known],
        custom_labels=["제주도 여행", "LLM이 지어낸 라벨", "제주도 여행"],
        people_count=2,
    )

    save_metadata("s_test", result, taxonomy, requested_custom_labels=["제주도 여행"])
    payload = load_metadata("s_test")

    assert payload["labels"] == [known]  # 사전 밖 라벨은 버려진다
    assert payload["custom_labels"] == ["제주도 여행"]  # 요청 목록에 없던 이름은 버려진다
    assert payload["people_count"] == 2
    assert payload["taxonomy_version"] == taxonomy.version


def test_save_metadata_caps_auto_labels_to_the_top_five(tmp_path, monkeypatch):
    """프롬프트는 '중요한 것부터 최대 5개'를 지시하고, 초과분은 저장 시 뒤에서 잘린다."""
    monkeypatch.chdir(tmp_path)
    taxonomy = default_photo_labels()
    many = [label.id for label in taxonomy.labels[:8]]
    result = PhotoMetadataOutput(long_description="설명", labels=many)

    save_metadata("s_cap", result, taxonomy, requested_custom_labels=[])
    payload = load_metadata("s_cap")

    assert payload["labels"] == many[:5]  # 중요도 순서(앞) 보존, 6개째부터 잘림


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


def test_prompt_uses_opaque_subject_reference_without_exposing_real_identity():
    prompt = build_user_prompt(
        raw_text="",
        custom_labels=[],
        detected_objects=[],
        taxonomy=default_photo_labels(),
        known_subjects=[
            {
                "subject_ref": "local_person_1",
                "kind": "person",
                "bbox": {"x_min": 0.1, "y_min": 0.1, "x_max": 0.4, "y_max": 0.8},
            }
        ],
    )
    assert "local_person_1" in prompt


# --- 업로드 → 트리거 배선 ---


def test_upload_triggers_metadata_with_parsed_fields(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    calls = []
    monkeypatch.setattr(
        "backend.api.capture.trigger_capture_pipeline",
        lambda *args: calls.append(args),
    )

    response = client.post(
        "/api/capture/frames",
        data={
            "session_id": "s_meta",
            "raw_text": "케이크 찍어줘",
            "custom_labels": json.dumps(["제주도 여행"], ensure_ascii=False),
            "detected_objects": json.dumps(["cake"]),
            # 셔터 순간 식별된 등록 인물·사물 — 설명이 이름으로 부르게 하는 재료 (2026-08-23)
            "known_subjects": json.dumps(
                [{"name": "유재석", "kind": "person", "bbox": {"x_min": 0.1, "y_min": 0.2, "x_max": 0.4, "y_max": 0.9}}],
                ensure_ascii=False,
            ),
        },
        files=[("representative_frame", ("rep.jpg", DUMMY_JPEG, "image/jpeg"))],
    )

    assert response.status_code == 200
    assert len(calls) == 1
    assert calls[0][0:4] == ("s_meta", 1, "케이크 찍어줘", [])
    assert calls[0][4] == ["제주도 여행"]
    assert calls[0][5] == ["cake"]
    assert calls[0][6] == [
        {
            "name": "유재석",
            "kind": "person",
            "bbox": {"x_min": 0.1, "y_min": 0.2, "x_max": 0.4, "y_max": 0.9},
        }
    ]


def test_known_subjects_are_rendered_into_prompt_with_position():
    from backend.mllm.description import describe_position, format_known_subjects

    text = format_known_subjects(
        [
            {"name": "유재석", "kind": "person", "bbox": {"x_min": 0.0, "y_min": 0.0, "x_max": 0.3, "y_max": 0.3}},
            {"name": "내 텀블러", "kind": "object", "bbox": None},
        ]
    )
    assert "유재석 (사람, 화면 왼쪽 위, 가로 0~30% · 세로 0~30%)" in text
    assert "내 텀블러 (사물)" in text  # bbox 없음 — 좌표도 없음
    assert describe_position({"x_min": 0.4, "y_min": 0.4, "x_max": 0.6, "y_max": 0.6}) == "화면 가운데"
    assert describe_position(None) is None


def test_intent_target_subject_is_marked_in_prompt():
    """발화 의도 대상은 표시가 붙어 MLLM이 그 대상 중심으로 설명하게 한다 (이름은 여전히 미전송)."""
    from backend.mllm.description import format_known_subjects

    text = format_known_subjects(
        [
            {
                "subject_ref": "local_track_3",
                "kind": "person",
                "bbox": {"x_min": 0.4, "y_min": 0.4, "x_max": 0.6, "y_max": 0.6},
                "intent_target": True,
            },
            {"subject_ref": "local_track_7", "kind": "person", "bbox": None},
        ]
    )
    assert "local_track_3 (사람, 화면 가운데, 가로 40~60% · 세로 40~60%) — 촬영 의도 대상" in text
    assert "local_track_7 (사람)" in text
    assert text.count("촬영 의도 대상") == 1


def test_unnamed_intent_target_is_masked_in_prompt():
    """이름 매핑 없는(named=False) 의도 대상은 토큰이 프롬프트에 노출되지 않는다 (2026-08-23)."""
    from backend.mllm.description import format_known_subjects

    text = format_known_subjects(
        [
            {
                "subject_ref": "local_track_9",
                "kind": "object",
                "bbox": {"x_min": 0.4, "y_min": 0.4, "x_max": 0.6, "y_max": 0.6},
                "intent_target": True,
                "named": False,
            }
        ]
    )
    assert "local_track_9" not in text
    assert "요청한 촬영 대상 (사물, 화면 가운데, 가로 40~60% · 세로 40~60%) — 촬영 의도 대상" in text


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
