"""안내 문장 정본과 음원 배치 생성기 검증.

핵심은 두 가지다. (1) 조사 마커가 받침에 맞게 확정되는가 — 그대로 치환하면 "사람를
찾았어요"가 나온다. (2) script.json 의 피사체가 실제로 검출 가능한 대상인가 — 검출되지
않는 단어로 음원을 구우면 영원히 재생되지 않는 파일이 생긴다.
"""

from __future__ import annotations

import re

import pytest

from ai.slot_parser import OBJECT_LABEL_KEYWORDS
from ai.tools.generate_voice_assets import asset_filename, select_utterances, text_sha1
from ai.voice_script import apply_josa, default_voice_script, has_final_consonant

# SubjectType 으로 직접 처리되어 OBJECT_LABEL_KEYWORDS 에는 없는 상위 카테고리.
SPECIAL_SUBJECTS = {"사람", "풍경"}


@pytest.mark.parametrize(
    ("word", "expected"),
    [
        ("사람", True),
        ("가방", True),
        ("노트북", True),
        ("강아지", False),
        ("고양이", False),
        ("자동차", False),
        ("", True),
        ("laptop", True),
    ],
)
def test_has_final_consonant(word: str, expected: bool) -> None:
    assert has_final_consonant(word) is expected


@pytest.mark.parametrize(
    ("template", "expected"),
    [
        ("사람{을/를} 찾았어요.", "사람을 찾았어요."),
        ("강아지{을/를} 찾았어요.", "강아지를 찾았어요."),
        ("가방{이/가} 안 보여요.", "가방이 안 보여요."),
        ("의자{이/가} 안 보여요.", "의자가 안 보여요."),
        ("노트북{은/는} 지원하지 않아요.", "노트북은 지원하지 않아요."),
        ("케이크{은/는} 지원하지 않아요.", "케이크는 지원하지 않아요."),
        ("서울{으로/로} 이동", "서울로 이동"),
        ("정면{으로/로} 이동", "정면으로 이동"),
        ("마커 없는 문장", "마커 없는 문장"),
    ],
)
def test_apply_josa(template: str, expected: str) -> None:
    assert apply_josa(template) == expected


def test_expansion_leaves_no_placeholder() -> None:
    """전개된 문장에 {…} 가 남아 있으면 그대로 읽혀 버린다."""
    for utterance in default_voice_script().expand():
        assert not re.search(r"[{}]", utterance.text), utterance


def test_expanded_ids_are_unique() -> None:
    utterances = default_voice_script().expand()
    ids = [utterance.id for utterance in utterances]
    assert len(ids) == len(set(ids))


def test_expansion_count_matches_declaration() -> None:
    """고정 + (변수 문장 × 값 개수) 가 실제 전개 수와 같아야 한다."""
    script = default_voice_script()
    expected = 0
    for line in script.lines:
        expected += len(script.values_for(line.vars[0])) if line.is_variable else 1
    assert len(script.expand()) == expected


def test_subjects_are_detectable() -> None:
    """음원을 구울 피사체는 실제 검출/파싱 가능한 어휘여야 한다."""
    for subject in default_voice_script().subjects:
        if subject in SPECIAL_SUBJECTS:
            continue
        assert subject in OBJECT_LABEL_KEYWORDS, f"{subject} 는 검출 대상 어휘가 아닙니다."


def test_directions_fit_move_template() -> None:
    """'조금 {방향} 이동해 주세요' 에 넣었을 때 조사가 이미 붙어 있어야 한다."""
    for direction in default_voice_script().directions:
        assert direction.endswith(("로", "으로")), direction


def test_asset_filename_has_no_dots_beyond_extension() -> None:
    name = asset_filename("search.found.강아지", "mp3")
    assert name == "search__found__강아지.mp3"
    assert name.count(".") == 1


def test_text_sha1_changes_with_text() -> None:
    assert text_sha1("사람을 찾았어요.") != text_sha1("사람를 찾았어요.")


def test_select_utterances_filters_by_prefix() -> None:
    utterances = default_voice_script().expand()
    selected = select_utterances(utterances, only="search", limit=None)
    assert selected
    assert all(utterance.id.startswith("search") for utterance in selected)


def test_select_utterances_limit() -> None:
    utterances = default_voice_script().expand()
    assert len(select_utterances(utterances, only=None, limit=3)) == 3


# --- "최종 기획 정리" 준수 ------------------------------------------------


def test_three_voice_presets_with_stable_default() -> None:
    """'안내 목소리 (프리셋 3종)' — 기본값은 1순위 프리셋."""
    script = default_voice_script()

    assert len(script.presets) == 3
    assert script.default_preset.id == "preset1"
    assert script.preset("preset3").id == "preset3"


def test_unknown_preset_is_rejected() -> None:
    with pytest.raises(ValueError):
        default_voice_script().preset("없는프리셋")


def test_playback_rates_match_plan() -> None:
    """'말하기 속도 (느림/보통/빠름)' — 재생 배속 0.8 / 1.0 / 1.5."""
    assert dict(default_voice_script().playback_rates) == {"느림": 0.8, "보통": 1.0, "빠름": 1.5}


@pytest.mark.parametrize(
    "line_id",
    [
        "preview.voice_sample",
        "settings.channel_guard",
        "capture.frame_complete",
        "capture.count_three",
        "capture.count_two",
        "capture.count_one",
        "framing.lost_short",
        "framing.nudge_once",
        "search.rescan",
    ],
)
def test_plan_mandated_lines_exist(line_id: str) -> None:
    """기획에서 문구까지 확정한 항목은 정본에 반드시 있어야 한다."""
    assert any(line.id == line_id for line in default_voice_script().lines)


def test_preview_sentence_is_verbatim() -> None:
    """미리듣기 문구는 실사용 문구와 동일해야 한다고 명시돼 있다."""
    line = next(line for line in default_voice_script().lines if line.id == "preview.voice_sample")
    assert line.text == "왼쪽으로 이동하세요. 사진이 촬영되었습니다."


def test_no_backward_walking_instruction() -> None:
    """'뒤로 한 걸음 가세요'는 위험하다고 명시적으로 배제됐다.

    핸드폰을 몸 쪽으로 당기라는 지시(framing.farther)는 사용자를 이동시키지 않으므로 허용된다.
    """
    forbidden = ("뒤로 가", "뒤로 한 걸음", "한 걸음 뒤", "뒤로 물러")
    for utterance in default_voice_script().expand():
        for phrase in forbidden:
            assert phrase not in utterance.text, f"{utterance.id}: {utterance.text}"
