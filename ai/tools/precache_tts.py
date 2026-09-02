"""TTS 프리캐싱 음원 생성 — SpeechCatalog 고정 문장을 SKT A.X 로 합성해 앱 assets 에 넣는다.

원래 생성 스크립트(scratchpad/skt_script_gen.py, gen_tts_catalog.py)가 개인 작업 폴더에만
있어 리포에 남지 않았다 — 새 문장을 추가할 때마다 필요한 도구라 정식으로 만든다 (2026-08-31).

사용법 (리포 루트에서, .env 에 SKT_TTS_APP_KEY 필요):

    python -m ai.tools.precache_tts u49 "폰 왼쪽 부분을 서류 쪽으로 기울여 주세요."

id 와 문장을 번갈아 여러 쌍 넘길 수 있다. 기본으로 aria/oliver 두 보이스 모두 합성하고,
이미 있는 파일은 건너뛴다(--force 로 재합성). 합성 후 SpeechCatalog.kt 의 TEXT_TO_ID 에
같은 (문장, id) 쌍을 손으로 추가해야 앱이 음원을 쓴다 — 텍스트가 완전 일치해야 한다.
"""

import argparse
import sys
from pathlib import Path

from ai.skt_tts_client import SKT_ALLOWED_VOICES, SktTtsClient, SktTtsError

DEFAULT_OUT_DIR = Path("frontend/app/src/main/assets/tts")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("pairs", nargs="+", help="id 문장 id 문장 ... (번갈아)")
    parser.add_argument("--voices", nargs="+", default=list(SKT_ALLOWED_VOICES), choices=SKT_ALLOWED_VOICES)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT_DIR, help="assets/tts 디렉터리")
    parser.add_argument("--force", action="store_true", help="이미 있는 mp3 도 다시 합성")
    args = parser.parse_args()

    if len(args.pairs) % 2 != 0:
        parser.error("id 와 문장은 쌍으로 넘겨야 합니다 (홀수 개 인자)")
    entries = list(zip(args.pairs[::2], args.pairs[1::2]))

    client = SktTtsClient()  # SKT_TTS_APP_KEY 없으면 여기서 RuntimeError
    failures = 0
    for voice in args.voices:
        voice_dir = args.out / voice
        if not voice_dir.is_dir():
            print(f"[중단] 보이스 디렉터리가 없습니다: {voice_dir} — 리포 루트에서 실행했는지 확인")
            return 1
        for asset_id, text in entries:
            target = voice_dir / f"{asset_id}.mp3"
            if target.exists() and not args.force:
                print(f"[건너뜀] {voice}/{asset_id}.mp3 (이미 있음)")
                continue
            try:
                audio = client.synthesize(text, voice)
            except SktTtsError as exc:
                print(f"[실패] {voice}/{asset_id}: {exc}")
                failures += 1
                continue
            target.write_bytes(audio)
            print(f"[생성] {voice}/{asset_id}.mp3 ({len(audio)} bytes) — {text[:24]}…")
    if failures:
        print(f"{failures}건 실패 — 네트워크·appKey 를 확인하고 다시 실행하면 실패분만 재시도됩니다.")
        return 1
    print("완료 — SpeechCatalog.kt 의 TEXT_TO_ID 에 (문장, id) 쌍을 추가하는 것을 잊지 마세요.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
