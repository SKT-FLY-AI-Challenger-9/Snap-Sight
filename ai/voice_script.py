# ai/voice_script.py
"""TTS 프리캐싱용 안내 문장 정본(ai/voice/script.json)의 로더·전개기.

"스크립트 - 준서" 문서 부록 1의 프리캐싱 설계에 대응한다. 고정 문장은 그대로, 변수 문장
({피사체}·{방향})은 조합을 모두 펼쳐 하나씩 음원으로 굽는다. 굽는 쪽은
ai/tools/generate_voice_assets.py 이고, 이 모듈은 "무엇을 구울지"만 결정한다.

조사 처리: 원문서는 "{피사체}를 찾았어요"처럼 조사를 고정해 뒀지만, 그대로 치환하면
"사람를 찾았어요"가 된다. 그래서 script.json은 {을/를} 같은 마커로 두고 여기서 앞 단어의
받침을 보고 고른다.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

VOICE_SCRIPT_PATH = Path(__file__).parent / "voice" / "script.json"

# 마커 → (받침 있을 때, 받침 없을 때)
_JOSA_FORMS: dict[str, tuple[str, str]] = {
    "을/를": ("을", "를"),
    "이/가": ("이", "가"),
    "은/는": ("은", "는"),
    "과/와": ("과", "와"),
    "으로/로": ("으로", "로"),
}

_JOSA_PATTERN = re.compile(r"\{(" + "|".join(re.escape(k) for k in _JOSA_FORMS) + r")\}")
_VAR_PATTERN = re.compile(r"\{(피사체|방향)\}")

_HANGUL_BASE = 0xAC00
_HANGUL_LAST = 0xD7A3
_JONGSEONG_COUNT = 28

# script.json의 vars 값 → 치환할 플레이스홀더 이름
_VAR_PLACEHOLDER = {"subject": "피사체", "direction": "방향"}


def has_final_consonant(word: str) -> bool:
    """한글 단어의 마지막 글자에 받침이 있으면 True.

    한글이 아닌 문자로 끝나면 받침이 있는 것으로 본다 (숫자·영문 뒤에는 '을/이/은'이
    자연스러운 경우가 많아 보수적으로 고른다).
    """
    if not word:
        return True
    last = word[-1]
    code = ord(last)
    if not (_HANGUL_BASE <= code <= _HANGUL_LAST):
        return True
    return (code - _HANGUL_BASE) % _JONGSEONG_COUNT != 0


_JONGSEONG_RIEUL = 8


def _pick_josa(marker: str, preceding: str) -> str:
    with_final, without_final = _JOSA_FORMS[marker]
    if marker == "으로/로" and preceding:
        # ㄹ 받침 뒤에는 '으로'가 아니라 '로'를 쓴다 (예: '오늘로', '서울로').
        code = ord(preceding[-1])
        if (
            _HANGUL_BASE <= code <= _HANGUL_LAST
            and (code - _HANGUL_BASE) % _JONGSEONG_COUNT == _JONGSEONG_RIEUL
        ):
            return without_final
    return with_final if has_final_consonant(preceding) else without_final


def apply_josa(text: str) -> str:
    """{을/를} 같은 조사 마커를 앞 단어의 받침에 따라 확정한다.

    앞에서부터 하나씩 확정한다 — 마커가 연달아 나오면 뒤 마커가 이미 확정된 앞부분을
    선행 텍스트로 봐야 하기 때문이다.
    """
    result = text
    while True:
        match = _JOSA_PATTERN.search(result)
        if match is None:
            return result
        josa = _pick_josa(match.group(1), result[: match.start()].rstrip())
        result = result[: match.start()] + josa + result[match.end() :]


@dataclass(frozen=True, slots=True)
class ScriptLine:
    """script.json의 한 줄. vars가 비어 있으면 고정 문장이다."""

    id: str
    group: str
    text: str
    vars: tuple[str, ...]

    @property
    def is_variable(self) -> bool:
        return bool(self.vars)


@dataclass(frozen=True, slots=True)
class Utterance:
    """실제로 음원 하나가 되는 확정 문장."""

    id: str
    line_id: str
    group: str
    text: str
    """치환·조사 확정까지 끝난 최종 문장."""
    variables: tuple[tuple[str, str], ...] = ()
    """(변수명, 값) 쌍. 고정 문장은 빈 튜플."""


@dataclass(frozen=True, slots=True)
class VoicePreset:
    """안내 목소리 프리셋 1종. 음원은 assets/voice/<id>/ 아래에 굽는다."""

    id: str
    label: str


@dataclass(frozen=True, slots=True)
class VoiceScript:
    """버전 있는 안내 문장 정본."""

    version: int
    source: str
    subjects: tuple[str, ...]
    directions: tuple[str, ...]
    lines: tuple[ScriptLine, ...]
    presets: tuple[VoicePreset, ...]
    playback_rates: tuple[tuple[str, float], ...]
    """(이름, 배속) — 음원은 하나만 굽고 재생 시 배속을 적용한다."""

    def preset(self, preset_id: str) -> VoicePreset:
        for candidate in self.presets:
            if candidate.id == preset_id:
                return candidate
        known = ", ".join(p.id for p in self.presets)
        raise ValueError(f"알 수 없는 프리셋: {preset_id!r} (가능: {known})")

    @property
    def default_preset(self) -> VoicePreset:
        return self.presets[0]

    def values_for(self, var_name: str) -> tuple[str, ...]:
        if var_name == "subject":
            return self.subjects
        if var_name == "direction":
            return self.directions
        raise ValueError(f"알 수 없는 변수: {var_name!r}")

    def expand(self) -> tuple[Utterance, ...]:
        """모든 문장을 음원 단위로 펼친다. 변수 1개까지만 지원한다."""
        utterances: list[Utterance] = []
        for line in self.lines:
            if not line.is_variable:
                utterances.append(
                    Utterance(
                        id=line.id,
                        line_id=line.id,
                        group=line.group,
                        text=apply_josa(line.text),
                    )
                )
                continue

            if len(line.vars) != 1:
                raise ValueError(f"{line.id}: 변수는 1개까지만 지원합니다 (받은 값: {line.vars}).")
            var_name = line.vars[0]
            placeholder = _VAR_PLACEHOLDER[var_name]
            for value in self.values_for(var_name):
                filled = line.text.replace(f"{{{placeholder}}}", value)
                utterances.append(
                    Utterance(
                        id=f"{line.id}.{value}",
                        line_id=line.id,
                        group=line.group,
                        text=apply_josa(filled),
                        variables=((var_name, value),),
                    )
                )
        return tuple(utterances)


def _parse(raw: dict) -> VoiceScript:
    lines: list[ScriptLine] = []
    seen: set[str] = set()
    for entry in raw["lines"]:
        line_id = entry["id"]
        if line_id in seen:
            raise ValueError(f"중복된 문장 id: {line_id!r}")
        seen.add(line_id)
        line_vars = tuple(entry.get("vars", ()))
        for var_name in line_vars:
            if var_name not in _VAR_PLACEHOLDER:
                raise ValueError(f"{line_id}: 알 수 없는 변수 {var_name!r}")
            placeholder = f"{{{_VAR_PLACEHOLDER[var_name]}}}"
            if placeholder not in entry["text"]:
                raise ValueError(f"{line_id}: 본문에 {placeholder} 가 없습니다.")
        leftover = {name for name in _VAR_PATTERN.findall(entry["text"])} - {
            _VAR_PLACEHOLDER[v] for v in line_vars
        }
        if leftover:
            raise ValueError(f"{line_id}: vars에 선언되지 않은 플레이스홀더 {leftover}")
        lines.append(
            ScriptLine(
                id=line_id,
                group=entry["group"],
                text=entry["text"],
                vars=line_vars,
            )
        )

    presets = tuple(VoicePreset(id=entry["id"], label=entry["label"]) for entry in raw["presets"])
    if not presets:
        raise ValueError("presets 는 최소 1개여야 합니다.")
    if len({preset.id for preset in presets}) != len(presets):
        raise ValueError("프리셋 id 가 중복됩니다.")

    return VoiceScript(
        version=int(raw["version"]),
        source=raw["source"],
        subjects=tuple(raw["subjects"]),
        directions=tuple(raw["directions"]),
        lines=tuple(lines),
        presets=presets,
        playback_rates=tuple(raw["playback_rates"].items()),
    )


@lru_cache(maxsize=1)
def default_voice_script() -> VoiceScript:
    """저장소에 커밋된 정본을 읽어 캐시한다."""
    raw = json.loads(VOICE_SCRIPT_PATH.read_text(encoding="utf-8"))
    return _parse(raw)
