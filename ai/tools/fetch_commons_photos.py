"""Wikimedia Commons 인물 사진 수집기 (PC 전용) — API 키 없이 구도 데이터 원천 확보.

Commons 의 Quality Images(리뷰어 심사를 통과한 사진)에서 인물 사진을 검색해 내려받는다.
Unsplash API 키가 없어도 동작하는 대안 소스다. 받은 사진은 ``composition_stats.py``
로 YOLO+포즈 좌표를 뽑아 구도 통계를 만든다.

사용:
    python -m ai.tools.fetch_commons_photos --count 120
    python -m ai.tools.fetch_commons_photos --query "woman standing full body" --count 60

기본 저장 위치는 ``dataset/commons_people/`` 이며 사진(*.jpg)은 .gitignore 로 커밋되지
않는다. 파일명은 Commons pageid 라 재실행 시 중복을 건너뛴다. 요청당 1080px 축소본을
받는다 (YOLO 640 입력에 충분).
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import requests

API_URL = "https://commons.wikimedia.org/w/api.php"
REQUEST_TIMEOUT_S = 30
PAGE_SIZE = 50
THUMB_WIDTH = 1080
PAUSE_S = 0.2  # Commons 에티켓 — 과도한 연속 요청 방지

# 단독 인물·전신 위주로 골라지도록 검색어를 여럿 순환한다 (중복은 pageid 로 걸러짐)
DEFAULT_QUERIES = [
    "portrait of a person standing",
    "full body portrait woman",
    "full body portrait man",
    "person standing street photography",
    "model posing full length",
]


def search_files(
    session: requests.Session, query: str, offset: int, quality_only: bool
) -> tuple[list[dict], int | None]:
    """파일 검색 — (파일 목록, 다음 offset). quality_only 면 Quality Images 로 한정한다."""
    prefix = 'filetype:bitmap incategory:"Quality images" ' if quality_only else "filetype:bitmap "
    response = session.get(
        API_URL,
        params={
            "action": "query",
            "format": "json",
            "generator": "search",
            "gsrsearch": prefix + query,
            "gsrnamespace": 6,  # File:
            "gsrlimit": PAGE_SIZE,
            "gsroffset": offset,
            "prop": "imageinfo",
            "iiprop": "url|size",
            "iiurlwidth": THUMB_WIDTH,
        },
        timeout=REQUEST_TIMEOUT_S,
    )
    response.raise_for_status()
    payload = response.json()
    pages = list(payload.get("query", {}).get("pages", {}).values())
    next_offset = payload.get("continue", {}).get("gsroffset")
    return pages, next_offset


def download(session: requests.Session, page: dict, out_dir: Path) -> bool:
    info = (page.get("imageinfo") or [{}])[0]
    url = info.get("thumburl") or info.get("url")
    if not url:
        return False
    # 썸네일 URL 은 쿼리 파라미터(utm 등)가 붙어 나온다 — 경로 부분으로만 확장자를 본다
    path_part = url.split("?", 1)[0].lower()
    if not path_part.endswith((".jpg", ".jpeg", ".png")):
        return False
    target = out_dir / f"{page['pageid']}.jpg"
    if target.exists():
        return False
    response = session.get(url, timeout=REQUEST_TIMEOUT_S)
    response.raise_for_status()
    target.write_bytes(response.content)
    return True


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(prog="python -m ai.tools.fetch_commons_photos")
    parser.add_argument("--query", default=None, help="단일 검색어 (생략 시 인물 기본 검색어 5종 순환)")
    parser.add_argument("--count", type=int, default=120, help="목표 다운로드 장수 (기본 120)")
    parser.add_argument("--out", type=Path, default=Path("dataset") / "commons_people")
    parser.add_argument(
        "--all-photos",
        action="store_true",
        help="Quality Images 한정을 해제한다 — 전신 사진처럼 QI 풀이 너무 작은 검색에 사용",
    )
    args = parser.parse_args()

    out_dir: Path = args.out
    out_dir.mkdir(parents=True, exist_ok=True)
    queries = [args.query] if args.query else DEFAULT_QUERIES

    session = requests.Session()
    session.headers["User-Agent"] = "SnapSight-composition-research/0.1 (student project)"

    downloaded = 0
    skipped = 0
    for query in queries:
        if downloaded >= args.count:
            break
        offset: int | None = 0
        while offset is not None and downloaded < args.count:
            try:
                pages, offset = search_files(session, query, offset, not args.all_photos)
            except requests.RequestException as exc:
                print(f"검색 실패 ({query!r}): {exc}", file=sys.stderr)
                break
            if not pages:
                break
            for page in pages:
                if downloaded >= args.count:
                    break
                try:
                    if download(session, page, out_dir):
                        downloaded += 1
                        print(f"[{downloaded}/{args.count}] {page['pageid']}.jpg ({query})")
                        time.sleep(PAUSE_S)
                    else:
                        skipped += 1
                except requests.RequestException as exc:
                    print(f"다운로드 실패 pageid={page.get('pageid')}: {exc}", file=sys.stderr)

    print(f"\n완료: 신규 {downloaded}장, 건너뜀 {skipped}건 → {out_dir}")
    print("다음: python -m ai.tools.composition_stats " + str(out_dir))
    return 0 if downloaded else 1


if __name__ == "__main__":
    sys.exit(main())
