# -*- coding: utf-8 -*-
"""배포 taxonomy 를 Objects365 365-class 에서 한국형 170-class 로 바꾼다.

바꾸는 것
  1. ai/taxonomy/objects365_yolo26_v1.json      classCount/labels/taxonomyId/modelId
  2. frontend/app/src/main/assets/..._labels.txt  170줄
  3. ai/slot_parser.py                          OBJECT_LABEL_KEYWORDS
  4. .../cv/SlotParser.kt                        OBJECT_LABEL_KEYWORDS (Kotlin 미러)

라벨 순서는 학습 노트북의 ALL_CLASSES 순서(= class id)와 반드시 같아야 한다.
어긋나면 ai/tools/export_tflite.py 의 verify_taxonomy() 가 export 를 막는다.

    python -m ai.tools.retarget_kr170 --labels <170줄 라벨 파일>
"""

from __future__ import annotations

import argparse
import ast
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TAXONOMY_JSON = ROOT / "ai" / "taxonomy" / "objects365_yolo26_v1.json"
LABELS_ASSET = ROOT / "frontend" / "app" / "src" / "main" / "assets" / "objects365_yolo26_v1_labels.txt"
SLOT_PARSER_PY = ROOT / "ai" / "slot_parser.py"
SLOT_PARSER_KT = (
    ROOT / "frontend" / "app" / "src" / "main" / "java"
    / "com" / "example" / "snap_sight" / "cv" / "SlotParser.kt"
)

TAXONOMY_ID = "snapsight_kr170_v1"
MODEL_ID = "yolo26n_kr170_v5"

# 170-class 에만 있는 개념의 한글 키워드. 1글자 키워드는 다른 단어에 우연히 포함되므로
# (이슈 #30) 쓰지 않는다 — 그래서 책/문/떡처럼 1음절인 것은 2음절 이상 대체어를 쓴다.
#
# egg plant 는 자연스러운 단어가 "가지" 뿐인데 "여러 가지" 같은 일상 표현에 그대로 포함돼
# 오매칭이 확실하므로 의도적으로 비워 둔다. 모델은 검출하지만 음성 지정은 안 된다.
NEW_KEYWORDS: dict[str, list[str]] = {
    "kimchi": ["김치"],
    "gimbap": ["김밥"],
    "mandu": ["만두"],
    "tteokbokki": ["떡볶이"],
    "ttoke": ["가래떡"],
    "side dish": ["반찬", "밑반찬"],
    "cutlery": ["수저", "식기"],
    "ladle": ["국자"],
    "rice spatula": ["밥주걱", "주걱"],
    "silicon spatula": ["실리콘주걱", "뒤집개"],
    "vegetable peeler": ["감자칼", "필러"],
    "tray": ["쟁반", "트레이"],
    "espresso machine": ["에스프레소머신", "커피머신"],
    "purifier": ["정수기", "공기청정기"],
    "toilet bowl": ["변기", "양변기"],
    "washstand": ["세면대", "세면기"],
    "door": ["출입문", "현관문", "문짝"],
    "window": ["창문"],
    "roof": ["지붕"],
    # "표지판" 은 traffic sign 이 이미 쓰고 있다 — sign 은 간판만 가져간다.
    "sign": ["간판"],
    "book": ["책자", "도서"],
    "hair brush": ["머리빗", "헤어브러시"],
    "muffler": ["머플러", "목도리"],
    "skating shoes": ["스케이트화", "스케이트"],
    "ball": ["축구공", "농구공", "야구공"],
    "basketball hoop": ["농구골대", "농구대", "농구링"],
    "goalpost": ["골대", "골포스트"],
    "billiards cue": ["당구채", "큐대"],
    "table tennis racket": ["탁구채", "탁구라켓"],
    "pilates equipment": ["필라테스기구", "필라테스"],
    "scooter": ["킥보드", "스쿠터"],
    "drone": ["드론"],
    "carabiner": ["카라비너"],
    "recorder": ["리코더"],
    "ocarina": ["오카리나"],
    "tambourine": ["탬버린"],
    "thermometer": ["체온계", "온도계"],
    "perilla leaf": ["깻잎"],
    "spring onion": ["대파", "쪽파"],
    "chili": ["고추"],
    "pimento": ["피망"],
    "squash": ["애호박"],
    "sweet potato": ["고구마"],
}


def read_python_table(text: str) -> tuple[dict[str, str], int, int]:
    start = text.index("OBJECT_LABEL_KEYWORDS = {")
    end = text.index("\n}", start) + 2
    literal = text[start + len("OBJECT_LABEL_KEYWORDS = "):end].strip()
    return ast.literal_eval(literal), start, end


# 170-class 에서 더 정확한 라벨이 생겨 기존 키워드의 배정을 옮기는 항목.
# "인형" 은 지금까지 teddy bear 에 붙어 있었지만 이제 doll 클래스가 있다.
# "곰인형"(teddy bear)은 더 길어서 그대로 우선한다.
REASSIGN: dict[str, str] = {
    "인형": "doll",
}

# 병합으로 사라진 개념의 키워드를 흡수 대상 라벨로 옮긴다.
# table -> desk 병합이라 "테이블"/"식탁"은 이제 desk 를 가리킨다.
MERGED_KEYWORDS: dict[str, list[str]] = {
    "desk": ["테이블", "식탁"],
}


def build_table(current: dict[str, str], labels: list[str]) -> dict[str, str]:
    valid = set(labels)
    table = {kw: lab for kw, lab in current.items() if lab in valid}
    for keyword, label in REASSIGN.items():
        if label not in valid:
            raise SystemExit(f"REASSIGN 대상 라벨이 없습니다: {label!r}")
        if keyword not in table:
            raise SystemExit(f"REASSIGN 대상 키워드가 표에 없습니다: {keyword!r}")
        table[keyword] = label
    for label, keywords in list(NEW_KEYWORDS.items()) + list(MERGED_KEYWORDS.items()):
        if label not in valid:
            raise SystemExit(f"키워드 대상 라벨이 없습니다: {label!r}")
        for keyword in keywords:
            if len(keyword) < 2:
                raise SystemExit(f"1글자 키워드는 금지: {keyword!r}")
            if keyword in table:
                raise SystemExit(f"키워드 충돌: {keyword!r} 이 이미 {table[keyword]!r} 에 배정됨")
            table[keyword] = label
    return table


def render_python(table: dict[str, str]) -> str:
    by_label: dict[str, list[str]] = {}
    for keyword, label in table.items():
        by_label.setdefault(label, []).append(keyword)
    lines = ["OBJECT_LABEL_KEYWORDS = {"]
    for label, keywords in by_label.items():
        pairs = ", ".join(f'"{k}": "{label}"' for k in keywords)
        lines.append(f"    {pairs},")
    lines.append("}")
    return "\n".join(lines)


def render_kotlin(table: dict[str, str]) -> str:
    by_label: dict[str, list[str]] = {}
    for keyword, label in table.items():
        by_label.setdefault(label, []).append(keyword)
    lines = ["    internal val OBJECT_LABEL_KEYWORDS: Map<String, String> = linkedMapOf("]
    for label, keywords in by_label.items():
        pairs = ", ".join(f'"{k}" to "{label}"' for k in keywords)
        lines.append(f"        {pairs},")
    lines.append("    )")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(prog="python -m ai.tools.retarget_kr170")
    parser.add_argument("--labels", type=Path, required=True, help="170줄 라벨 파일 (줄 번호 = class id)")
    arguments = parser.parse_args()

    labels = [line.strip() for line in arguments.labels.read_text(encoding="utf-8").splitlines() if line.strip()]
    if len(labels) != len(set(label.casefold() for label in labels)):
        raise SystemExit("라벨에 대소문자 무시 중복이 있습니다")
    if labels[0] != "person":
        raise SystemExit(f"person 이 class id 0 이어야 합니다 (현재 {labels[0]!r})")
    print(f"[1/4] 라벨 {len(labels)}개 확인 — person id 0")

    # 파일을 하나라도 쓰기 전에 키워드 표를 먼저 만들어 본다.
    # 중간에 실패하면 일부만 반영된 상태로 남아 원인을 찾기 어렵다.
    py_text = SLOT_PARSER_PY.read_text(encoding="utf-8")
    current, start, end = read_python_table(py_text)
    table = build_table(current, labels)

    # 1. taxonomy JSON
    payload = json.loads(TAXONOMY_JSON.read_text(encoding="utf-8"))
    payload["taxonomyId"] = TAXONOMY_ID
    payload["modelId"] = MODEL_ID
    payload["modelSha256"] = "0" * 64   # ai.tools.stamp_taxonomy_hash 로 실제 값을 채운다
    payload["classCount"] = len(labels)
    payload["labels"] = labels
    TAXONOMY_JSON.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"[2/4] taxonomy 갱신 — {TAXONOMY_JSON.name} ({len(labels)} classes, id={TAXONOMY_ID})")

    # 2. 라벨 자산
    LABELS_ASSET.write_text("\n".join(labels) + "\n", encoding="utf-8")
    print(f"      라벨 자산 갱신 — {LABELS_ASSET.name}")

    # 3. Python 키워드 표
    dropped = len(current) - sum(1 for lab in current.values() if lab in set(labels))
    SLOT_PARSER_PY.write_text(
        py_text[:start] + render_python(table) + py_text[end:], encoding="utf-8"
    )
    print(f"[3/4] Python 키워드 표 — {len(current)} → {len(table)}개 (제거 {dropped}, 추가 {sum(len(v) for v in NEW_KEYWORDS.values())})")

    # 4. Kotlin 미러
    kt_text = SLOT_PARSER_KT.read_text(encoding="utf-8")
    kt_start = kt_text.index("    internal val OBJECT_LABEL_KEYWORDS")
    kt_end = kt_text.index("\n    )", kt_start) + len("\n    )")
    SLOT_PARSER_KT.write_text(
        kt_text[:kt_start] + render_kotlin(table) + kt_text[kt_end:], encoding="utf-8"
    )
    print(f"[4/4] Kotlin 미러 갱신 — {len(table)}개")

    uncovered = [lab for lab in labels if lab != "person" and lab not in set(table.values())]
    print(f"\n키워드 없는 클래스 {len(uncovered)}개(검출은 되지만 음성 지정 불가):")
    print(f"  {uncovered}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
