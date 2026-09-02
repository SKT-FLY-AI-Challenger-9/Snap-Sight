"""Unsplash 인물 사진 수집기 (PC 전용) — 구도 데이터셋의 원천 사진 다운로드.

잘 찍힌(큐레이션된) 인물 사진을 Unsplash 공식 API 로 검색해 내려받는다.
인스타그램 스크래핑 대신 라이선스가 명확한 소스를 쓴다. 받은 사진은
``composition_stats.py`` 로 YOLO+포즈 좌표를 뽑아 구도 통계를 만든다.

준비:
    1) https://unsplash.com/developers 에서 앱 등록 → Access Key 발급
    2) 저장소 루트 .env 에 ``UNSPLASH_ACCESS_KEY=...`` 추가

사용:
    python -m ai.tools.fetch_unsplash_photos --query "full body portrait" --count 60
    python -m ai.tools.fetch_unsplash_photos --query "standing person street" --count 90 --out .\\dataset\\street

기본 저장 위치는 ``dataset/unsplash_<query slug>/`` 이며, 사진(*.jpg)은 .gitignore
전역 규칙으로 커밋되지 않는다. 파일명은 Unsplash 사진 ID 라 재실행 시 중복 다운로드를
건너뛴다. demo 수준 트래픽(시간당 50요청) 안에서 동작하도록 페이지당 30장씩 요청한다.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import time
from pathlib import Path

import requests
from dotenv import load_dotenv

load_dotenv()

API_ROOT = "https://api.unsplash.com"
PER_PAGE = 30  # Unsplash search 최대값
REQUEST_TIMEOUT_S = 20
# demo 앱 rate limit(50/h) 보호 — 페이지 검색 1회 + 사진당 다운로드 트리거 1회
DOWNLOAD_PAUSE_S = 0.3


def slugify(query: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", query.lower()).strip("-") or "photos"


def search_page(session: requests.Session, query: str, page: int, orientation: str) -> dict:
    response = session.get(
        f"{API_ROOT}/search/photos",
        params={
            "query": query,
            "page": page,
            "per_page": PER_PAGE,
            "orientation": orientation,
            "content_filter": "high",
        },
        timeout=REQUEST_TIMEOUT_S,
    )
    response.raise_for_status()
    return response.json()


def download_photo(session: requests.Session, photo: dict, out_dir: Path) -> bool:
    photo_id = photo["id"]
    target = out_dir / f"{photo_id}.jpg"
    if target.exists():
        return False

    # API 가이드라인: 실제 다운로드 시 download_location 을 호출해 집계에 반영한다
    download_location = photo.get("links", {}).get("download_location")
    if download_location:
        try:
            session.get(download_location, timeout=REQUEST_TIMEOUT_S)
        except requests.RequestException:
            pass  # 집계 실패가 다운로드 자체를 막을 이유는 없다

    url = photo["urls"]["regular"]  # 최장변 ~1080px — YOLO 640 입력에 충분
    response = session.get(url, timeout=REQUEST_TIMEOUT_S)
    response.raise_for_status()
    target.write_bytes(response.content)
    return True


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(prog="python -m ai.tools.fetch_unsplash_photos")
    parser.add_argument("--query", required=True, help='검색어 (예: "full body portrait")')
    parser.add_argument("--count", type=int, default=60, help="목표 다운로드 장수 (기본 60)")
    parser.add_argument(
        "--orientation",
        choices=("portrait", "landscape", "squarish"),
        default="portrait",
        help="사진 방향 필터 (기본 portrait — 앱 촬영 화면과 동일)",
    )
    parser.add_argument("--out", type=Path, default=None, help="저장 폴더 (기본 dataset/unsplash_<slug>)")
    args = parser.parse_args()

    access_key = os.getenv("UNSPLASH_ACCESS_KEY")
    if not access_key:
        print(
            "UNSPLASH_ACCESS_KEY 가 없습니다. https://unsplash.com/developers 에서 "
            "앱을 등록하고 .env 에 UNSPLASH_ACCESS_KEY=... 를 추가하세요.",
            file=sys.stderr,
        )
        return 1

    out_dir: Path = args.out or Path("dataset") / f"unsplash_{slugify(args.query)}"
    out_dir.mkdir(parents=True, exist_ok=True)

    session = requests.Session()
    session.headers["Authorization"] = f"Client-ID {access_key}"
    session.headers["Accept-Version"] = "v1"

    downloaded = 0
    skipped = 0
    page = 1
    while downloaded < args.count:
        try:
            payload = search_page(session, args.query, page, args.orientation)
        except requests.HTTPError as exc:
            status = exc.response.status_code if exc.response is not None else "?"
            if status == 403:
                print("rate limit 도달(시간당 50요청) — 잠시 후 다시 실행하세요.", file=sys.stderr)
            else:
                print(f"검색 실패 (HTTP {status}): {exc}", file=sys.stderr)
            break

        results = payload.get("results", [])
        if not results:
            print("더 이상 검색 결과가 없습니다.")
            break

        for photo in results:
            if downloaded >= args.count:
                break
            try:
                if download_photo(session, photo, out_dir):
                    downloaded += 1
                    print(f"[{downloaded}/{args.count}] {photo['id']}.jpg")
                    time.sleep(DOWNLOAD_PAUSE_S)
                else:
                    skipped += 1
            except requests.RequestException as exc:
                print(f"다운로드 실패 {photo['id']}: {exc}", file=sys.stderr)

        if page >= payload.get("total_pages", page):
            break
        page += 1

    print(f"\n완료: 신규 {downloaded}장, 기존 {skipped}장 건너뜀 → {out_dir}")
    print("다음: python -m ai.tools.composition_stats " + str(out_dir))
    return 0 if downloaded or skipped else 1


if __name__ == "__main__":
    sys.exit(main())
