"""ai/slot_parser.OBJECT_LABEL_KEYWORDS가 아직 커버하지 못한 Objects365 라벨을 찾는다.

번역 API 키가 없어서(이슈 #30) 실시간 번역 자동화 대신, LLM이 taxonomy 전체를 한 번에
검토해 한글 키워드를 만드는 방식을 택했다. 이 스크립트는 그 결과물(OBJECT_LABEL_KEYWORDS)이
taxonomy 대비 얼마나 비어있는지, 정확히 어떤 라벨이 비었는지 알려주는 유지보수용 도구다.
taxonomy가 업데이트되거나(② 모델 교체) 커버리지를 더 늘리고 싶을 때 실행해서 다음 작업
대상을 바로 확인할 수 있다.

실행:

    python -m ai.tools.list_uncovered_object_labels
"""

from __future__ import annotations

from ai.slot_parser import OBJECT_LABEL_KEYWORDS
from ai.taxonomy import OBJECTS365_YOLO26


def find_uncovered_labels() -> list[str]:
    """OBJECT_LABEL_KEYWORDS에 매핑된 값이 하나도 없는 Objects365 object 라벨을 반환한다."""
    covered = set(OBJECT_LABEL_KEYWORDS.values())
    return sorted(label for label in OBJECTS365_YOLO26.object_labels if label not in covered)


def main() -> None:
    all_labels = OBJECTS365_YOLO26.object_labels
    uncovered = find_uncovered_labels()
    covered_count = len(all_labels) - len(uncovered)

    print(f"전체 object 라벨: {len(all_labels)}개")
    print(f"커버됨: {covered_count}개 ({covered_count / len(all_labels):.0%})")
    print(f"미커버: {len(uncovered)}개")
    print()
    for label in uncovered:
        print(label)


if __name__ == "__main__":
    main()
