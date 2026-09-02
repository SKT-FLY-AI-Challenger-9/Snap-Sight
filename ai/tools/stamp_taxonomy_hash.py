# -*- coding: utf-8 -*-
"""배포한 .tflite 의 SHA-256 을 taxonomy JSON 의 modelSha256 에 기록한다.

taxonomy 는 "이 라벨 순서가 이 모델 파일의 것"이라는 계약이다. 모델을 갈아끼우고
해시를 그대로 두면 그 계약이 거짓이 되고, 나중에 어떤 모델이 배포됐는지 추적할 수 없다.

    python -m ai.tools.stamp_taxonomy_hash
    python -m ai.tools.stamp_taxonomy_hash --model path/to/other.tflite --check
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_TAXONOMY = ROOT / "ai" / "taxonomy" / "objects365_yolo26_v1.json"
DEFAULT_MODEL = (
    ROOT / "frontend" / "app" / "src" / "main" / "assets" / "objects365_yolo26_v1.tflite"
)


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m ai.tools.stamp_taxonomy_hash")
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--taxonomy", type=Path, default=DEFAULT_TAXONOMY)
    parser.add_argument(
        "--check",
        action="store_true",
        help="기록만 확인하고 쓰지 않는다. 어긋나면 exit 1 — CI 용.",
    )
    arguments = parser.parse_args(argv)

    if not arguments.model.exists():
        print(f"[x] 모델 파일이 없습니다: {arguments.model}", file=sys.stderr)
        return 1

    actual = sha256_of(arguments.model)
    payload = json.loads(arguments.taxonomy.read_text(encoding="utf-8"))
    recorded = payload.get("modelSha256", "")

    if arguments.check:
        if recorded == actual:
            print(f"일치 — {arguments.model.name} sha256={actual[:16]}…")
            return 0
        print(
            f"[x] taxonomy 의 modelSha256 이 실제 모델과 다릅니다.\n"
            f"    기록: {recorded}\n"
            f"    실제: {actual}\n"
            f"    python -m ai.tools.stamp_taxonomy_hash 로 갱신하세요.",
            file=sys.stderr,
        )
        return 1

    if recorded == actual:
        print(f"이미 최신입니다 — sha256={actual[:16]}…")
        return 0

    payload["modelSha256"] = actual
    arguments.taxonomy.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"modelSha256 갱신 — {arguments.taxonomy.name}")
    print(f"  {recorded[:16]}… → {actual[:16]}…")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
