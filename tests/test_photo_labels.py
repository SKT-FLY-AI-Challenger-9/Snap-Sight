# tests/test_photo_labels.py
"""사진 검색용 고정 라벨 사전(ai/photo_labels.py) 로더·검증 테스트 (기능 3-A)."""

import json
from pathlib import Path

import pytest

from ai.photo_labels import PHOTO_LABELS_PATH, default_photo_labels, load_photo_labels

REPO_ROOT = Path(__file__).parent.parent
ANDROID_ASSET_PATH = REPO_ROOT / "frontend" / "app" / "src" / "main" / "assets" / "photo_labels.json"


def test_default_dictionary_loads_and_has_unique_ids():
    taxonomy = default_photo_labels()
    assert taxonomy.version >= 1
    assert len(taxonomy.labels) > 0
    ids = [label.id for label in taxonomy.labels]
    assert len(ids) == len(set(ids))


def test_android_asset_is_identical_to_python_source():
    """라벨링(백엔드)과 검색(앱)이 같은 사전을 쓴다는 계약 — 두 파일은 항상 동일해야 한다."""
    python_side = json.loads(PHOTO_LABELS_PATH.read_text(encoding="utf-8"))
    android_side = json.loads(ANDROID_ASSET_PATH.read_text(encoding="utf-8"))
    assert python_side == android_side


def test_validate_label_ids_drops_unknown_ids():
    taxonomy = default_photo_labels()
    known = next(iter(taxonomy.ids()))
    assert taxonomy.validate_label_ids([known, "made-up-label"]) == [known]


def test_validate_label_ids_deduplicates_in_model_order():
    taxonomy = default_photo_labels()
    first, second = [label.id for label in taxonomy.labels[:2]]
    assert taxonomy.validate_label_ids([first, second, first]) == [first, second]


def test_prompt_catalog_lists_every_label():
    taxonomy = default_photo_labels()
    catalog = taxonomy.prompt_catalog()
    for label in taxonomy.labels:
        assert label.id in catalog
        assert label.name in catalog


@pytest.mark.parametrize(
    "broken",
    [
        {"labels": [{"id": "a", "name": "가"}]},  # version 누락
        {"version": 1, "labels": []},  # 빈 labels
        {"version": 1, "labels": [{"id": "a", "name": ""}]},  # 빈 name
        {"version": 1, "labels": [{"id": "a", "name": "가"}, {"id": "a", "name": "나"}]},  # 중복 id
        {"version": 1, "labels": [{"id": "a", "name": "가", "synonyms": [1]}]},  # 잘못된 synonyms
    ],
)
def test_broken_dictionary_is_rejected(tmp_path, broken):
    path = tmp_path / "photo_labels.json"
    path.write_text(json.dumps(broken, ensure_ascii=False), encoding="utf-8")
    with pytest.raises(ValueError):
        load_photo_labels(path)
