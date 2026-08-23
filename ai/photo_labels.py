# ai/photo_labels.py
"""사진 검색용 고정 라벨 사전(ai/taxonomy/photo_labels.json)의 로더·검증기.

docs/feature-expansion-plan.md 기능 3-A — 라벨링(백엔드 MLLM)과 검색(앱)이 같은 사전을
공유하는 폐쇄형(closed-set) 설계의 Python 쪽 정본이다. Android 는 assets 에 복사된 같은
JSON 을 읽는다 (tests/test_photo_labels.py 가 두 파일의 동일성을 검증).
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

PHOTO_LABELS_PATH = Path(__file__).parent / "taxonomy" / "photo_labels.json"


@dataclass(frozen=True, slots=True)
class PhotoLabel:
    """고정 사전의 라벨 1개. synonyms 는 음성 검색 발화와의 매칭에 쓰인다."""

    id: str
    name: str
    synonyms: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class PhotoLabelTaxonomy:
    """버전 있는 고정 라벨 사전. 사진 레코드에는 version 을 함께 저장해 재라벨링 근거로 쓴다."""

    version: int
    labels: tuple[PhotoLabel, ...]

    def ids(self) -> frozenset[str]:
        return frozenset(label.id for label in self.labels)

    def validate_label_ids(self, label_ids: list[str]) -> list[str]:
        """사전에 존재하는 id만 입력 순서대로 한 번씩 남긴다."""
        known = self.ids()
        seen: set[str] = set()
        validated: list[str] = []
        for label_id in label_ids:
            if label_id not in known or label_id in seen:
                continue
            seen.add(label_id)
            validated.append(label_id)
        return validated

    def prompt_catalog(self) -> str:
        """LLM 라벨링 프롬프트에 넣을 '고를 수 있는 라벨 목록' 텍스트."""
        lines = [f"- {label.id}: {label.name}" for label in self.labels]
        return "\n".join(lines)


def load_photo_labels(path: Path = PHOTO_LABELS_PATH) -> PhotoLabelTaxonomy:
    """사전 파일을 읽고 검증한다. 형식이 깨져 있으면 명확한 예외를 던진다."""
    payload = json.loads(path.read_text(encoding="utf-8"))
    version = payload.get("version")
    if not isinstance(version, int) or version < 1:
        raise ValueError("photo_labels.json: version 은 1 이상의 정수여야 합니다")

    raw_labels = payload.get("labels")
    if not isinstance(raw_labels, list) or not raw_labels:
        raise ValueError("photo_labels.json: labels 는 비어 있지 않은 배열이어야 합니다")

    labels: list[PhotoLabel] = []
    seen_ids: set[str] = set()
    for entry in raw_labels:
        label_id = entry.get("id")
        name = entry.get("name")
        synonyms = entry.get("synonyms", [])
        if not isinstance(label_id, str) or not label_id.strip():
            raise ValueError(f"photo_labels.json: 잘못된 라벨 id — {entry}")
        if label_id in seen_ids:
            raise ValueError(f"photo_labels.json: 중복 라벨 id — {label_id}")
        if not isinstance(name, str) or not name.strip():
            raise ValueError(f"photo_labels.json: 라벨 {label_id} 의 name 이 비어 있습니다")
        if not isinstance(synonyms, list) or any(
            not isinstance(item, str) or not item.strip() for item in synonyms
        ):
            raise ValueError(f"photo_labels.json: 라벨 {label_id} 의 synonyms 형식이 잘못됐습니다")
        seen_ids.add(label_id)
        labels.append(PhotoLabel(id=label_id, name=name, synonyms=tuple(synonyms)))

    return PhotoLabelTaxonomy(version=version, labels=tuple(labels))


@lru_cache(maxsize=1)
def default_photo_labels() -> PhotoLabelTaxonomy:
    """저장소 기본 사전의 캐시된 인스턴스."""
    return load_photo_labels()
