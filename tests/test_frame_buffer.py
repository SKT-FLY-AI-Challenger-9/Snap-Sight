# tests/test_frame_buffer.py
"""backend.storage.frame_buffer의 저장·조회 로직을 확인하는 테스트."""

import pytest

from backend.storage.frame_buffer import (
    load_session_frame_paths,
    save_candidate_frame,
    save_representative_frame,
)

DUMMY_JPEG = b"\xff\xd8\xff\xe0fake-jpeg-bytes"


def test_load_session_frame_paths_returns_representative_and_sorted_candidates(
    tmp_path, monkeypatch
):
    """저장된 대표 컷 경로와, 인덱스 순으로 정렬된 후보 프레임 경로 목록을 반환한다."""
    monkeypatch.chdir(tmp_path)
    save_representative_frame("session-1", "rep.jpg", DUMMY_JPEG)
    save_candidate_frame("session-1", 1, "cand1.jpg", DUMMY_JPEG)
    save_candidate_frame("session-1", 0, "cand0.jpg", DUMMY_JPEG)

    representative_path, candidate_paths = load_session_frame_paths("session-1")

    assert representative_path.name == "representative.jpg"
    assert [path.name for path in candidate_paths] == ["candidate_0.jpg", "candidate_1.jpg"]


def test_load_session_frame_paths_sorts_candidates_numerically_past_nine(tmp_path, monkeypatch):
    """후보 프레임이 10장을 넘어도 문자열이 아닌 숫자 순서로 정렬된다."""
    monkeypatch.chdir(tmp_path)
    save_representative_frame("session-2", "rep.jpg", DUMMY_JPEG)
    for index in (2, 10, 1):
        save_candidate_frame("session-2", index, f"cand{index}.jpg", DUMMY_JPEG)

    _, candidate_paths = load_session_frame_paths("session-2")

    assert [path.name for path in candidate_paths] == [
        "candidate_1.jpg",
        "candidate_2.jpg",
        "candidate_10.jpg",
    ]


def test_load_session_frame_paths_raises_when_representative_missing(tmp_path, monkeypatch):
    """대표 컷이 저장되지 않은 세션은 명확한 에러로 실패한다."""
    monkeypatch.chdir(tmp_path)

    with pytest.raises(FileNotFoundError, match="session-missing"):
        load_session_frame_paths("session-missing")
