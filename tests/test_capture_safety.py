"""Security and resource-boundary tests for capture storage and APIs."""

import os
import time
from io import BytesIO

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from PIL import Image

from ai.photo_labels import default_photo_labels
from backend.api.capture import router as capture_router
from backend.api.guards import reset_rate_limit_state
from backend.config import DEFAULT_CAPTURE_TTL_SECONDS
from backend.mllm.description import save_description
from backend.mllm.metadata import PhotoMetadataOutput, save_metadata
from backend.mllm.prompts import FrameComparisonResult
from backend.storage.capture_state import (
    begin_capture_revision,
    load_capture_state,
    update_capture_state,
)
from backend.storage.comparison_result import save_comparison_result
from backend.storage.frame_buffer import (
    cleanup_expired_sessions,
    save_representative_frame,
    validate_session_id,
)


def _make_jpeg() -> bytes:
    output = BytesIO()
    Image.new("RGB", (2, 2), "white").save(output, format="JPEG")
    return output.getvalue()


DUMMY_JPEG = _make_jpeg()


@pytest.fixture
def client(monkeypatch):
    monkeypatch.delenv("SNAPSIGHT_API_TOKEN", raising=False)
    monkeypatch.delenv("SNAPSIGHT_RATE_LIMIT_PER_MINUTE", raising=False)
    monkeypatch.delenv("SNAPSIGHT_MAX_REQUEST_BYTES", raising=False)
    monkeypatch.setattr("backend.api.capture.trigger_capture_pipeline", lambda *args: None)
    reset_rate_limit_state()
    app = FastAPI()
    app.include_router(capture_router)
    return TestClient(app)


@pytest.mark.parametrize(
    "session_id",
    ["../outside", "..\\outside", "C:\\Windows\\Temp\\outside", "/tmp/outside", "a/b"],
)
def test_session_ids_cannot_escape_capture_root(tmp_path, monkeypatch, session_id):
    monkeypatch.chdir(tmp_path)
    with pytest.raises(ValueError):
        save_representative_frame(session_id, "frame.jpg", DUMMY_JPEG)
    assert not (tmp_path / "outside").exists()


def test_strict_session_mode_accepts_only_canonical_uuid(monkeypatch):
    monkeypatch.setenv("SNAPSIGHT_REQUIRE_UUID_SESSION_IDS", "1")
    canonical = "123e4567-e89b-12d3-a456-426614174000"
    assert validate_session_id(canonical) == canonical
    with pytest.raises(ValueError):
        validate_session_id("s_20260823_120000")


def test_upload_ignores_untrusted_filename_and_uses_server_jpeg_name(
    tmp_path, monkeypatch, client
):
    monkeypatch.chdir(tmp_path)
    response = client.post(
        "/api/capture/frames",
        data={"session_id": "safe-file-name"},
        files=[
            (
                "representative_frame",
                ("../../payload.pth", DUMMY_JPEG, "image/jpeg"),
            )
        ],
    )
    assert response.status_code == 200
    session_dir = tmp_path / "captures" / "safe-file-name"
    assert (session_dir / "representative.jpg").read_bytes() == DUMMY_JPEG
    assert not list(tmp_path.rglob("*.pth"))


def test_upload_rejects_oversized_file_before_creating_session(tmp_path, monkeypatch, client):
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("SNAPSIGHT_MAX_CAPTURE_FILE_BYTES", "8")
    response = client.post(
        "/api/capture/frames",
        data={"session_id": "too-large"},
        files=[("representative_frame", ("frame.jpg", DUMMY_JPEG, "image/jpeg"))],
    )
    assert response.status_code == 413
    assert not (tmp_path / "captures" / "too-large").exists()


def test_upload_rejects_non_jpeg_content(tmp_path, monkeypatch, client):
    monkeypatch.chdir(tmp_path)
    response = client.post(
        "/api/capture/frames",
        data={"session_id": "not-an-image"},
        files=[("representative_frame", ("frame.jpg", b"not jpeg", "image/jpeg"))],
    )
    assert response.status_code == 415
    assert not (tmp_path / "captures" / "not-an-image").exists()


def test_upload_accepts_private_opaque_subject_reference(tmp_path, monkeypatch, client):
    monkeypatch.chdir(tmp_path)
    calls = []
    monkeypatch.setattr(
        "backend.api.capture.trigger_capture_pipeline", lambda *args: calls.append(args)
    )
    response = client.post(
        "/api/capture/frames",
        data={
            "session_id": "opaque-subject",
            "known_subjects": (
                '[{"subject_ref":"local_person_1","kind":"person",'
                '"bbox":{"x_min":0.1,"y_min":0.1,"x_max":0.4,"y_max":0.8}}]'
            ),
        },
        files=[("representative_frame", ("frame.jpg", DUMMY_JPEG, "image/jpeg"))],
    )
    assert response.status_code == 200
    assert calls[0][6] == [
        {
            "subject_ref": "local_person_1",
            "kind": "person",
            "bbox": {"x_min": 0.1, "y_min": 0.1, "x_max": 0.4, "y_max": 0.8},
        }
    ]


def test_candidate_rotation_is_validated_and_pixels_are_saved_upright(
    tmp_path, monkeypatch, client
):
    monkeypatch.chdir(tmp_path)
    source = Image.new("RGB", (8, 4), "red")
    encoded = BytesIO()
    source.save(encoded, format="JPEG")
    response = client.post(
        "/api/capture/frames",
        data={
            "session_id": "upright-candidate",
            "candidate_scores": '[{"blur_score":0.2,"rotation_degrees":90}]',
        },
        files=[
            ("representative_frame", ("rep.jpg", encoded.getvalue(), "image/jpeg")),
            ("candidate_frames", ("candidate.jpg", encoded.getvalue(), "image/jpeg")),
        ],
    )
    assert response.status_code == 200
    with Image.open(tmp_path / "captures" / "upright-candidate" / "candidate_0.jpg") as saved:
        assert saved.size == (4, 8)


def test_upload_rejects_unsupported_candidate_rotation_before_writing(
    tmp_path, monkeypatch, client
):
    monkeypatch.chdir(tmp_path)
    response = client.post(
        "/api/capture/frames",
        data={
            "session_id": "bad-rotation",
            "candidate_scores": '[{"blur_score":0.2,"rotation_degrees":45}]',
        },
        files=[
            ("representative_frame", ("rep.jpg", DUMMY_JPEG, "image/jpeg")),
            ("candidate_frames", ("candidate.jpg", DUMMY_JPEG, "image/jpeg")),
        ],
    )
    assert response.status_code == 422
    assert not (tmp_path / "captures" / "bad-rotation").exists()


def test_optional_api_token_is_enforced_when_configured(tmp_path, monkeypatch, client):
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("SNAPSIGHT_API_TOKEN", "test-secret")
    files = [("representative_frame", ("frame.jpg", DUMMY_JPEG, "image/jpeg"))]
    assert (
        client.post("/api/capture/frames", data={"session_id": "auth-test"}, files=files).status_code
        == 401
    )
    response = client.post(
        "/api/capture/frames",
        data={"session_id": "auth-test"},
        files=files,
        headers={"X-Snap-Sight-Token": "test-secret"},
    )
    assert response.status_code == 200
    assert client.get("/api/capture/auth-test/final-frame").status_code == 401


def test_optional_rate_limit_returns_429(monkeypatch, client):
    monkeypatch.setenv("SNAPSIGHT_RATE_LIMIT_PER_MINUTE", "1")
    reset_rate_limit_state()
    assert client.get("/api/capture/missing/result").status_code == 404
    assert client.get("/api/capture/missing/result").status_code == 429


def test_new_revision_clears_only_managed_stale_artifacts(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    first = begin_capture_revision(
        "revision-test",
        lambda: save_representative_frame("revision-test", "frame.jpg", DUMMY_JPEG),
    )
    session_dir = tmp_path / "captures" / "revision-test"
    (session_dir / "description.json").write_text("{}", encoding="utf-8")
    (session_dir / "user-note.txt").write_text("keep", encoding="utf-8")

    second = begin_capture_revision(
        "revision-test",
        lambda: save_representative_frame("revision-test", "frame.jpg", b"new"),
    )
    assert first.capture_revision == 1
    assert second.capture_revision == 2
    assert not (session_dir / "description.json").exists()
    assert (session_dir / "user-note.txt").read_text(encoding="utf-8") == "keep"
    assert load_capture_state("revision-test").capture_revision == 2


def test_final_frame_endpoint_returns_revision_bound_private_jpeg(
    tmp_path, monkeypatch, client
):
    monkeypatch.chdir(tmp_path)
    state = begin_capture_revision(
        "final-download",
        lambda: save_representative_frame("final-download", "frame.jpg", DUMMY_JPEG),
    )
    update_capture_state(
        "final-download",
        state.capture_revision,
        status="ready",
        final_frame_id="candidate_1",
    )
    response = client.get(
        f"/api/capture/final-download/final-frame?capture_revision={state.capture_revision}"
    )
    assert response.status_code == 200
    assert response.content == DUMMY_JPEG
    assert response.headers["content-type"].startswith("image/jpeg")
    assert response.headers["x-capture-revision"] == "1"
    assert response.headers["x-final-frame-id"] == "candidate_1"
    assert response.headers["cache-control"] == "private, no-store"
    assert (
        client.get("/api/capture/final-download/final-frame?capture_revision=2").status_code
        == 409
    )


def test_final_frame_endpoint_enforces_configured_size_limit(tmp_path, monkeypatch, client):
    monkeypatch.chdir(tmp_path)
    state = begin_capture_revision(
        "oversized-final",
        lambda: save_representative_frame("oversized-final", "frame.jpg", DUMMY_JPEG),
    )
    update_capture_state(
        "oversized-final",
        state.capture_revision,
        status="ready",
        final_frame_id="representative",
    )
    monkeypatch.setenv("SNAPSIGHT_MAX_CAPTURE_FILE_BYTES", "8")
    assert client.get("/api/capture/oversized-final/final-frame").status_code == 413


def test_final_frame_endpoint_rejects_encoded_windows_traversal(tmp_path, monkeypatch, client):
    monkeypatch.chdir(tmp_path)
    response = client.get("/api/capture/..%5Coutside/final-frame")
    assert response.status_code == 422
    assert not (tmp_path / "outside").exists()


def test_ttl_cleanup_removes_only_expired_session_directories(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    old = tmp_path / "captures" / "old-session"
    fresh = tmp_path / "captures" / "fresh-session"
    old.mkdir(parents=True)
    fresh.mkdir(parents=True)
    os.utime(old, (10, 10))
    os.utime(fresh, (190, 190))
    assert cleanup_expired_sessions(100, now=200) == 1
    assert not old.exists()
    assert fresh.exists()


def test_upload_applies_default_24_hour_capture_ttl(tmp_path, monkeypatch, client):
    monkeypatch.chdir(tmp_path)
    monkeypatch.delenv("SNAPSIGHT_CAPTURE_TTL_SECONDS", raising=False)
    expired = tmp_path / "captures" / "expired-session"
    expired.mkdir(parents=True)
    expired_at = time.time() - DEFAULT_CAPTURE_TTL_SECONDS - 1
    os.utime(expired, (expired_at, expired_at))

    response = client.post(
        "/api/capture/frames",
        data={"session_id": "fresh-upload"},
        files=[("representative_frame", ("frame.jpg", DUMMY_JPEG, "image/jpeg"))],
    )

    assert response.status_code == 200
    assert not expired.exists()
    assert (tmp_path / "captures" / "fresh-upload").is_dir()


def test_polling_contract_repeats_revision_and_canonical_frame_id(
    tmp_path, monkeypatch, client
):
    monkeypatch.chdir(tmp_path)
    state = begin_capture_revision(
        "poll-contract",
        lambda: save_representative_frame("poll-contract", "frame.jpg", DUMMY_JPEG),
    )
    save_comparison_result(
        "poll-contract",
        FrameComparisonResult(
            improved=False,
            selected_frame=None,
            reason="Representative retained.",
        ),
    )
    save_description(
        "poll-contract",
        "A cup is on a table.",
        capture_revision=state.capture_revision,
        final_frame_id="representative",
    )
    save_metadata(
        "poll-contract",
        PhotoMetadataOutput(
            brief_description="A cup is on a table.",
            long_description="A white cup is on a wooden table.",
        ),
        default_photo_labels(),
        [],
        capture_revision=state.capture_revision,
        final_frame_id="representative",
    )
    update_capture_state(
        "poll-contract",
        state.capture_revision,
        status="ready",
        final_frame_id="representative",
    )

    result = client.get("/api/capture/poll-contract/result").json()
    description = client.get("/api/capture/poll-contract/description").json()
    metadata = client.get("/api/capture/poll-contract/metadata").json()

    assert result["status"] == description["status"] == metadata["status"] == "done"
    for payload in (result, description, metadata):
        assert payload["capture_revision"] == state.capture_revision
        assert payload["final_frame_id"] == "representative"
        assert payload["retry_after_seconds"] is None
    assert description["description"] == metadata["brief_description"]


def test_failed_capture_state_is_terminal_for_every_polling_endpoint(
    tmp_path, monkeypatch, client
):
    monkeypatch.chdir(tmp_path)
    state = begin_capture_revision(
        "failed-contract",
        lambda: save_representative_frame("failed-contract", "frame.jpg", DUMMY_JPEG),
    )
    update_capture_state("failed-contract", state.capture_revision, status="failed")

    for endpoint in ("result", "description", "metadata"):
        payload = client.get(f"/api/capture/failed-contract/{endpoint}").json()
        assert payload["status"] == "failed"
        assert payload["retry_after_seconds"] is None
        assert payload["capture_revision"] == state.capture_revision
        assert payload["final_frame_id"] is None
