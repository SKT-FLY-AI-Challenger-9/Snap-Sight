"""안내 문장 정본(ai/voice/script.json)을 음원으로 구워 Android assets 에 넣는다.

"스크립트 - 준서" 부록 1의 프리캐싱 설계 구현이다. 고정 문장과 변수 조합을 모두 펼쳐
문장 하나당 파일 하나를 만들고, 앱은 네트워크 없이 그 파일을 재생한다. 실시간 합성이
필요한 것은 결과 설명뿐이며 그건 이 도구가 다루지 않는다.

엔진은 교체 가능하다. 지금은 ElevenLabs 만 구현돼 있고, NUGU 는 임의 텍스트를 파일로
받아오는 API 가 없어(클라우드 directive 방식) 이 배치 경로에 넣을 수 없다.

실행:

    python -m ai.tools.generate_voice_assets --dry-run     # 무엇을 구울지만 확인
    python -m ai.tools.generate_voice_assets               # 없는 것만 생성
    python -m ai.tools.generate_voice_assets --force       # 전부 다시 생성

주요 옵션:

    --out ...        출력 뿌리 (기본: frontend/app/src/main/assets/voice)
    --preset ID      안내 목소리 프리셋 (기본: 정본 첫 프리셋). <out>/<preset>/ 에 굽는다
    --voice-id ID    엔진 목소리 id. 프리셋마다 다른 목소리를 주려면 함께 지정한다
    --only PREFIX    id 가 PREFIX 로 시작하는 것만 (예: --only search)
    --limit N        앞에서 N 개만 — API 쿼터 확인용
    --force          이미 있는 파일도 다시 생성
    --dry-run        호출 없이 목록·개수만 출력

생성물은 mp3 와 manifest.json 이다. manifest 는 앱이 "문장 id → 파일" 을 찾는 색인이자,
문장이 바뀌었는지 판별하는 근거(text_sha1)다. 문장을 고치면 sha 가 달라지므로 다음
실행에서 그 항목만 다시 구워진다.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from dataclasses import dataclass
from pathlib import Path

from ai.voice_script import Utterance, default_voice_script

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUT_DIR = REPOSITORY_ROOT / "frontend" / "app" / "src" / "main" / "assets" / "voice"
MANIFEST_NAME = "manifest.json"


class VoiceAssetError(RuntimeError):
    """사용자가 고칠 수 있는, 메시지가 분명한 실패."""


def text_sha1(text: str) -> str:
    return hashlib.sha1(text.encode("utf-8")).hexdigest()


def asset_filename(utterance_id: str, extension: str) -> str:
    """id 를 파일명으로 쓴다. 한글 변수값이 들어가므로 안전한 형태로 바꾼다."""
    safe = utterance_id.replace(".", "__")
    return f"{safe}.{extension}"


@dataclass(frozen=True, slots=True)
class Engine:
    """TTS 엔진 어댑터. 텍스트 하나를 오디오 바이트로 바꾼다."""

    name: str
    extension: str
    synthesize: object
    """Callable[[str], bytes]."""


def build_elevenlabs_engine(voice_id: str | None) -> Engine:
    from ai.tts_client import ElevenLabsTTSClient

    client = ElevenLabsTTSClient()
    if voice_id:
        client.voice_id = voice_id
    return Engine(name="elevenlabs", extension="mp3", synthesize=client.synthesize)


ENGINE_BUILDERS = {"elevenlabs": build_elevenlabs_engine}


def select_utterances(
    utterances: tuple[Utterance, ...], only: str | None, limit: int | None
) -> list[Utterance]:
    selected = [u for u in utterances if only is None or u.id.startswith(only)]
    if only is not None and not selected:
        raise VoiceAssetError(f"--only {only!r} 에 해당하는 문장이 없습니다.")
    if limit is not None:
        selected = selected[:limit]
    return selected


def load_manifest(path: Path) -> dict[str, dict]:
    if not path.exists():
        return {}
    raw = json.loads(path.read_text(encoding="utf-8"))
    return {entry["id"]: entry for entry in raw.get("assets", [])}


def needs_rebuild(
    utterance: Utterance, previous: dict[str, dict], out_dir: Path, extension: str
) -> bool:
    entry = previous.get(utterance.id)
    if entry is None:
        return True
    if entry.get("text_sha1") != text_sha1(utterance.text):
        return True
    return not (out_dir / entry["file"]).exists()


def write_manifest(
    path: Path,
    version: int,
    engine_name: str,
    entries: list[dict],
    preset_id: str = "",
    preset_label: str = "",
) -> None:
    payload = {
        "version": version,
        "engine": engine_name,
        "preset": preset_id,
        "preset_label": preset_label,
        "generated_from": "ai/voice/script.json",
        "assets": sorted(entries, key=lambda e: e["id"]),
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def parse_arguments(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="python -m ai.tools.generate_voice_assets",
        description="안내 문장을 음원으로 구워 Android assets 에 넣는다.",
    )
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT_DIR)
    parser.add_argument("--engine", choices=sorted(ENGINE_BUILDERS), default="elevenlabs")
    parser.add_argument(
        "--preset",
        default=None,
        help="안내 목소리 프리셋 id (기본: 정본의 첫 프리셋). assets/voice/<preset>/ 에 굽는다.",
    )
    parser.add_argument(
        "--voice-id",
        default=None,
        help="엔진의 목소리 id. 생략하면 .env 의 ELEVENLABS_VOICE_ID 를 쓴다.",
    )
    parser.add_argument("--only", default=None, help="id 접두사 필터 (예: search)")
    parser.add_argument("--limit", type=int, default=None, help="앞에서 N 개만")
    parser.add_argument("--force", action="store_true", help="이미 있는 것도 다시 생성")
    parser.add_argument("--dry-run", action="store_true", help="호출 없이 목록만 출력")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_arguments(argv)
    script = default_voice_script()
    preset = script.preset(args.preset) if args.preset else script.default_preset
    utterances = script.expand()
    selected = select_utterances(utterances, args.only, args.limit)

    if args.dry_run:
        print(f"정본 v{script.version} — 문장 {len(script.lines)}개 → 음원 {len(utterances)}개")
        print(f"프리셋: {preset.id} ({preset.label})")
        print(f"이번 대상: {len(selected)}개\n")
        for utterance in selected:
            print(f"  {utterance.id:44} {utterance.text}")
        return 0

    # 프리셋마다 폴더를 나눈다 — 앱은 선택된 프리셋 폴더의 manifest 만 읽는다.
    out_dir: Path = args.out / preset.id
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = out_dir / MANIFEST_NAME
    previous = load_manifest(manifest_path)

    engine = ENGINE_BUILDERS[args.engine](args.voice_id)

    entries: list[dict] = []
    created = 0
    skipped = 0
    for index, utterance in enumerate(selected, start=1):
        filename = asset_filename(utterance.id, engine.extension)
        entry = {
            "id": utterance.id,
            "line_id": utterance.line_id,
            "group": utterance.group,
            "text": utterance.text,
            "text_sha1": text_sha1(utterance.text),
            "file": filename,
        }

        if not args.force and not needs_rebuild(utterance, previous, out_dir, engine.extension):
            skipped += 1
            entries.append(previous[utterance.id])
            continue

        try:
            audio = engine.synthesize(utterance.text)
        except Exception as exc:
            write_manifest(
                manifest_path, script.version, engine.name, entries, preset.id, preset.label
            )
            raise VoiceAssetError(
                f"{utterance.id} 합성 실패: {exc}\n"
                f"여기까지 {created}개 생성됨. 원인 해결 후 다시 실행하면 이어서 진행합니다."
            ) from exc

        (out_dir / filename).write_bytes(audio)
        entry["bytes"] = len(audio)
        entries.append(entry)
        created += 1
        print(f"[{index}/{len(selected)}] {utterance.id} ({len(audio):,}B)")

    # 이번에 건드리지 않은 기존 항목도 manifest 에 유지한다 (--only/--limit 사용 시).
    touched = {entry["id"] for entry in entries}
    for asset_id, entry in previous.items():
        if asset_id not in touched:
            entries.append(entry)

    write_manifest(manifest_path, script.version, engine.name, entries, preset.id, preset.label)
    print(f"\n[{preset.id}] 생성 {created} / 건너뜀 {skipped} → {out_dir}")
    print(f"manifest: {manifest_path}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except VoiceAssetError as error:
        print(f"오류: {error}", file=sys.stderr)
        sys.exit(1)
