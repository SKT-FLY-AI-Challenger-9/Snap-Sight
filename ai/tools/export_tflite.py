"""PyTorch Objects365 체크포인트를 Android assets 용 TFLite 로 내보낸다.

`ai/on_device_cv` 의 PC 파이프라인과 `com.example.snap_sight.cv` 의 Android 포팅이
**같은 class ID 순서**를 쓴다는 것이 이 저장소의 전제다. 그래서 export 전에
checkpoint 의 ``model.names`` 를 taxonomy 와 대조하고, 어긋나면 아무것도 만들지 않는다.
class ID 가 한 칸이라도 밀리면 detector 는 조용히 엉뚱한 label 을 붙인다.

실행:

    python -m pip install "ultralytics" tensorflow
    python -m ai.tools.export_tflite

주요 옵션:

    --imgsz 640          모델 입력 한 변 (Android detector 가 자동 인식하므로 자유롭게 바꿔도 된다)
    --half               fp16 export (크기 절반, 정확도 거의 동일)
    --int8 --data ...    int8 양자화. ultralytics 가 calibration 데이터셋 yaml 을 요구한다.
    --assets-dir ...     복사 대상 (기본: frontend/app/src/main/assets)
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

from ai.taxonomy import OBJECTS365_YOLO26

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_WEIGHTS = REPOSITORY_ROOT / "yolo26n-kr170-v5.pt"
DEFAULT_ASSETS_DIR = REPOSITORY_ROOT / "frontend" / "app" / "src" / "main" / "assets"
DEFAULT_MODEL_NAME = "objects365_yolo26_v1.tflite"
DEFAULT_LABELS_NAME = "objects365_yolo26_v1_labels.txt"


class ExportError(RuntimeError):
    """사용자가 고칠 수 있는, 메시지가 분명한 실패."""


def parse_arguments(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="python -m ai.tools.export_tflite",
        description="Objects365 YOLO 체크포인트를 Android assets 용 TFLite 로 export",
    )
    parser.add_argument("--weights", type=Path, default=DEFAULT_WEIGHTS)
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--half", action="store_true", help="fp16 export")
    parser.add_argument("--int8", action="store_true", help="int8 양자화 export")
    parser.add_argument(
        "--data",
        type=str,
        default=None,
        help="int8 calibration 데이터셋 yaml (--int8 과 함께 사용)",
    )
    parser.add_argument("--assets-dir", type=Path, default=DEFAULT_ASSETS_DIR)
    parser.add_argument("--model-name", default=DEFAULT_MODEL_NAME)
    parser.add_argument("--labels-name", default=DEFAULT_LABELS_NAME)
    parser.add_argument(
        "--labels-only",
        action="store_true",
        help="라벨 파일만 생성한다. taxonomy 만 있으면 되므로 ultralytics/tensorflow 가 필요 없다.",
    )
    parser.add_argument(
        "--skip-taxonomy-check",
        action="store_true",
        help="class ID 순서 검증을 건너뛴다. 다른 taxonomy 모델을 실험할 때만 사용.",
    )
    return parser.parse_args(argv)


def verify_taxonomy(model: object) -> None:
    """checkpoint 의 class ID 순서가 배포 taxonomy 와 같은지 확인한다."""

    names = getattr(model, "names", None)
    if not names:
        raise ExportError("checkpoint 에서 model.names 를 읽을 수 없습니다")
    try:
        OBJECTS365_YOLO26.validate_model_names(names)
    except ValueError as exc:
        raise ExportError(
            f"{exc}\n"
            "이 checkpoint 는 ai/taxonomy/objects365_yolo26_v1.json 과 class ID 순서가 다릅니다. "
            "전용 taxonomy 와 selector adapter 없이 Android 에 넣으면 label 이 뒤섞입니다."
        ) from exc
    print(f"[1/4] taxonomy 검증 통과 — {OBJECTS365_YOLO26.class_count}개 class, 순서 일치")


def export_model(model: object, arguments: argparse.Namespace) -> Path:
    export_kwargs: dict[str, object] = {
        "format": "tflite",
        "imgsz": arguments.imgsz,
        # Android detector 가 자체 NMS 를 수행한다. NMS 를 모델에 심으면 출력 텐서
        # 개수·형태가 바뀌어 TfLiteYoloDetector 의 layout 자동 판별이 깨진다.
        "nms": False,
    }
    if arguments.half:
        export_kwargs["half"] = True
    if arguments.int8:
        export_kwargs["int8"] = True
        if arguments.data:
            export_kwargs["data"] = arguments.data
        else:
            print(
                "[!] --int8 인데 --data 가 없습니다. ultralytics 기본 calibration 을 쓰므로 "
                "정확도가 크게 떨어질 수 있습니다."
            )

    print(f"[2/4] TFLite export 중… ({export_kwargs})")
    try:
        exported = model.export(**export_kwargs)  # type: ignore[attr-defined]
    except AssertionError as exc:
        # ultralytics 의 TFLite/LiteRT 변환은 onnx2tf 툴체인에 의존하는데 Windows 를 지원하지 않는다.
        # 패키지를 더 깐다고 해결되지 않으므로 우회 경로를 알려준다.
        if "Linux" in str(exc) or "macOS" in str(exc):
            raise ExportError(
                f"{exc}\n"
                "ultralytics 의 TFLite/LiteRT 변환은 Linux x86 / macOS 에서만 동작합니다.\n"
                "우회 경로:\n"
                "  1) WSL(Ubuntu)에서 이 스크립트를 그대로 실행 — 저장소는 /mnt/c/... 로 보입니다\n"
                "  2) Google Colab 등 리눅스 런타임에서 export 후 .tflite 만 assets 로 복사\n"
                "  Android 쪽 코드는 그대로 두면 됩니다. 필요한 건 .tflite 파일 하나뿐입니다."
            ) from exc
        raise ExportError(str(exc)) from exc
    exported_path = Path(exported)
    if not exported_path.exists():
        raise ExportError(f"export 는 끝났지만 결과 파일이 없습니다: {exported_path}")
    return exported_path


def write_labels(arguments: argparse.Namespace) -> Path:
    assets_dir: Path = arguments.assets_dir
    assets_dir.mkdir(parents=True, exist_ok=True)
    labels_target = assets_dir / arguments.labels_name
    # 줄 번호가 곧 class ID 다. 정렬하거나 빈 줄을 넣으면 매핑이 깨진다.
    labels_target.write_text(
        "\n".join(OBJECTS365_YOLO26.labels) + "\n",
        encoding="utf-8",
    )
    return labels_target


def write_assets(exported_path: Path, arguments: argparse.Namespace) -> tuple[Path, Path]:
    assets_dir: Path = arguments.assets_dir
    assets_dir.mkdir(parents=True, exist_ok=True)

    model_target = assets_dir / arguments.model_name
    shutil.copyfile(exported_path, model_target)
    labels_target = write_labels(arguments)

    size_mb = model_target.stat().st_size / (1024 * 1024)
    print("[3/4] 자산 배치 완료")
    print(f"      모델 : {model_target}  ({size_mb:.1f} MB)")
    print(f"      라벨 : {labels_target}  ({OBJECTS365_YOLO26.class_count}줄)")
    return model_target, labels_target


def describe_signature(model_path: Path) -> None:
    """출력 텐서 형태를 보여준다 — Android 쪽 layout 자동 판별을 눈으로 확인하는 용도."""

    try:
        import tensorflow as tf  # noqa: PLC0415
    except ImportError:
        print("[4/4] tensorflow 가 없어 텐서 형태 확인을 건너뜁니다 (export 자체는 정상)")
        return

    interpreter = tf.lite.Interpreter(model_path=str(model_path))
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()

    print("[4/4] 텐서 형태")
    for detail in input_details:
        print(f"      입력 {detail['shape'].tolist()} {detail['dtype'].__name__}")
    for detail in output_details:
        print(f"      출력 {detail['shape'].tolist()} {detail['dtype'].__name__}")

    expected_channels = 4 + OBJECTS365_YOLO26.class_count
    first_output = output_details[0]["shape"].tolist()
    if len(first_output) != 3:
        print(f"      [!] TfLiteYoloDetector 는 [1, A, B] 3차원 출력을 기대합니다: {first_output}")
    elif first_output[2] == expected_channels:
        print(f"      → layout ANCHORS_FIRST, anchors={first_output[1]}")
    elif first_output[1] == expected_channels:
        print(f"      → layout CHANNELS_FIRST, anchors={first_output[2]}")
    elif first_output[2] == 6:
        print(f"      → layout END_TO_END (xyxy+conf+cls), detections={first_output[1]}")
    else:
        print(
            f"      [!] 알 수 없는 출력 형태 {first_output} — "
            f"4+nc={expected_channels} 또는 6 을 기대합니다. "
            "TfLiteYoloDetector 의 layout 판별을 함께 고쳐야 합니다."
        )
    if len(output_details) > 1:
        print(
            f"      [!] 출력 텐서가 {len(output_details)}개입니다. detector 는 0번만 읽습니다 — "
            "NMS 가 모델에 심어진 export 가 아닌지 확인하세요."
        )


def main(argv: list[str] | None = None) -> int:
    arguments = parse_arguments(argv)

    if arguments.labels_only:
        labels_target = write_labels(arguments)
        print(f"라벨 파일 생성: {labels_target}  ({OBJECTS365_YOLO26.class_count}줄)")
        return 0

    if not arguments.weights.exists():
        print(f"[x] 체크포인트가 없습니다: {arguments.weights}", file=sys.stderr)
        return 1

    try:
        from ultralytics import YOLO  # noqa: PLC0415
    except ImportError:
        print(
            "[x] ultralytics 가 설치돼 있지 않습니다.\n"
            '    python -m pip install "ultralytics" tensorflow',
            file=sys.stderr,
        )
        return 1

    try:
        model = YOLO(str(arguments.weights))
        if arguments.skip_taxonomy_check:
            print("[1/4] taxonomy 검증 건너뜀 (--skip-taxonomy-check)")
        else:
            verify_taxonomy(model)
        exported_path = export_model(model, arguments)
        model_target, _ = write_assets(exported_path, arguments)
        describe_signature(model_target)
    except ExportError as exc:
        print(f"[x] {exc}", file=sys.stderr)
        return 1

    print(
        "\n완료. Android Studio 에서 앱을 다시 빌드하면 TfLiteYoloDetector 가 자동으로 로드합니다.\n"
        "로그 태그 SnapSightCV 에서 입력/출력 형태와 layout 판별 결과를 확인하세요."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
