# tests/test_list_uncovered_object_labels.py
"""ai/tools/list_uncovered_object_labels가 taxonomy와 OBJECT_LABEL_KEYWORDS를 정확히 대조하는지 확인."""

from ai.slot_parser import OBJECT_LABEL_KEYWORDS
from ai.taxonomy import OBJECTS365_YOLO26
from ai.tools.list_uncovered_object_labels import find_uncovered_labels


def test_uncovered_labels_are_actually_uncovered():
    uncovered = find_uncovered_labels()
    covered = set(OBJECT_LABEL_KEYWORDS.values())

    assert covered.isdisjoint(uncovered)


def test_covered_plus_uncovered_equals_all_object_labels():
    uncovered = set(find_uncovered_labels())
    covered = set(OBJECT_LABEL_KEYWORDS.values())

    assert covered | uncovered == set(OBJECTS365_YOLO26.object_labels)
