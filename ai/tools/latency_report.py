"""SnapLatency logcat 을 구간별 지연 표(p50/p95)로 집계한다.

프론트의 ``SessionLatencyTracker`` 가 세션마다 JSON 한 줄을 logcat(SnapLatency 태그)에 남긴다.
모든 시각은 폰 시계 하나로 잰 오프셋(ms)이므로 서버 시계 오차가 없다. 서버 구간은
"폰이 결과를 받아본 시각" 기준이라 폴링 대기(최대 ~8초)가 포함된다 = 사용자 체감 기준.

사용:

    adb logcat -d -s SnapLatency:I > latency.log
    python -m ai.tools.latency_report latency.log

    # 파일 없이 바로 (adb 가 PATH 에 있어야 함)
    python -m ai.tools.latency_report

여러 파일을 주면 합쳐서 집계한다. partial(안내까지 못 간 세션) 행은 표에 표시되고,
집계에는 값이 있는 구간만 들어간다.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

# (표시 이름, 시작 마크, 끝 마크)
INTERVALS = [
    ("① 탭→발화 이해", "listening_start", "utterance_understood"),
    ("   ↳ 말하기(탭까지)", "listening_start", "stt_done"),
    ("   ↳ 서버 변환", "stt_done", "utterance_understood"),
    ("② 발화 이해→셔터 (조준)", "utterance_understood", "shutter"),
    ("③ 셔터→업로드 완료", "shutter", "upload_done"),
    ("④+⑤ 업로드→통합 이해(베스트컷+설명)", "upload_done", "understanding_done"),
    ("⑤' 업로드→상세 설명(수동 경로)", "upload_done", "description_done"),
    ("⑥ 이해→안내 재생 시작", "understanding_done", "announce_start"),
    ("총: 탭→안내 재생 시작", "listening_start", "announce_start"),
]

LINE_RE = re.compile(r"SnapLatency\s*:\s*(\{.*\})\s*$")


def parse_lines(lines) -> list[dict]:
    rows = []
    for line in lines:
        match = LINE_RE.search(line)
        if not match:
            continue
        try:
            row = json.loads(match.group(1))
        except json.JSONDecodeError:
            continue
        if isinstance(row.get("marks_ms"), dict):
            rows.append(row)
    return rows


def percentile(values: list[float], q: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return float("nan")
    position = (len(ordered) - 1) * q
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def main() -> int:
    # Windows 콘솔(cp949)에서 ↳·① 같은 문자가 UnicodeEncodeError 를 내지 않게
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(prog="python -m ai.tools.latency_report")
    parser.add_argument("logs", nargs="*", type=Path, help="adb logcat 덤프 파일(없으면 adb 직접 실행)")
    args = parser.parse_args()

    if args.logs:
        lines = []
        for path in args.logs:
            lines += path.read_text(encoding="utf-8", errors="replace").splitlines()
    else:
        try:
            output = subprocess.run(
                ["adb", "logcat", "-d", "-s", "SnapLatency:I"],
                capture_output=True, text=True, encoding="utf-8", errors="replace", check=True,
            ).stdout
        except (OSError, subprocess.CalledProcessError) as exc:
            print(f"adb logcat 실행 실패: {exc} — 파일을 인자로 주세요", file=sys.stderr)
            return 1
        lines = output.splitlines()

    rows = parse_lines(lines)
    if not rows:
        print("SnapLatency 로그가 없습니다. 앱에서 세션을 진행한 뒤 다시 수집하세요.", file=sys.stderr)
        return 1

    # 세션별 표
    print(f"세션 {len(rows)}개 (partial {sum(1 for r in rows if r.get('partial'))}개)\n")
    header = ["세션", "partial"] + [name for name, _, _ in INTERVALS]
    print("| " + " | ".join(header) + " |")
    print("|" + "---|" * len(header))
    samples: dict[str, list[float]] = {name: [] for name, _, _ in INTERVALS}
    for row in rows:
        marks = row["marks_ms"]
        cells = [str(row.get("session", "?"))[:8], "O" if row.get("partial") else ""]
        for name, start, end in INTERVALS:
            if start in marks and end in marks and marks[end] >= marks[start]:
                seconds = (marks[end] - marks[start]) / 1000.0
                samples[name].append(seconds)
                cells.append(f"{seconds:.2f}")
            else:
                cells.append("-")
        print("| " + " | ".join(cells) + " |")

    # 집계
    print("\n| 구간 | n | 평균(s) | p50 | p95 | 최소 | 최대 |")
    print("|---|---|---|---|---|---|---|")
    for name, _, _ in INTERVALS:
        values = samples[name]
        if not values:
            print(f"| {name} | 0 | - | - | - | - | - |")
            continue
        print(
            f"| {name} | {len(values)} | {sum(values) / len(values):.2f} | "
            f"{percentile(values, 0.50):.2f} | {percentile(values, 0.95):.2f} | "
            f"{min(values):.2f} | {max(values):.2f} |"
        )
    print("\n주: ②는 사용자 조준 행동 포함. ④+⑤·⑤' 는 서버 처리에 폴링 대기(2→4→8s 백오프)가 포함된 체감값.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
