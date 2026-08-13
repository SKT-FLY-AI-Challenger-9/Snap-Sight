"""PC video/webcam demo for Snap-Sight object detection and tracking."""

from __future__ import annotations

import argparse
import json
import math
import time
from pathlib import Path
from typing import TextIO

import cv2

from ai.on_device_cv.contracts import FrameResult
from ai.on_device_cv.detectors import UltralyticsDetectorConfig, UltralyticsYoloDetector
from ai.on_device_cv.pipeline import OnDeviceCVPipeline, PipelineConfig
from ai.on_device_cv.target_selection import TargetSelectionResult, TargetSelector
from ai.on_device_cv.trackers import ByteTrackLiteConfig, ByteTrackLiteTracker
from ai.on_device_cv.visualization import draw_frame_result
from ai.target_spec import TargetSpec


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Detect and track every supported Objects365 object in a video stream."
    )
    parser.add_argument(
        "--source",
        default="0",
        help="Video path/URL or webcam index (default: 0).",
    )
    parser.add_argument(
        "--model",
        default="yolo26n-objv1-150.pt",
        help="Official model name or a local fine-tuned/exported model path.",
    )
    parser.add_argument(
        "--confidence",
        type=float,
        default=0.25,
        help="Minimum confidence for new tracks and public output (default: 0.25).",
    )
    parser.add_argument(
        "--matching-confidence",
        type=float,
        default=0.10,
        help="Low detector threshold used only to recover tracks (default: 0.10).",
    )
    parser.add_argument("--imgsz", type=int, default=640, help="Detector input size.")
    parser.add_argument("--max-detections", type=int, default=300)
    parser.add_argument("--device", default="cpu", help="Ultralytics device, e.g. cpu, 0, mps.")
    parser.add_argument(
        "--frame-stride",
        type=int,
        default=1,
        help="Analyze one out of every N input frames (default: 1).",
    )
    parser.add_argument(
        "--target-spec",
        type=Path,
        help=(
            "Optional TargetSpec v0.1 JSON. Detection/tracking still runs for every "
            "class, while the overlay and JSONL contain only intent-matching candidates."
        ),
    )
    parser.add_argument("--match-iou", type=float, default=0.30)
    parser.add_argument("--low-match-iou", type=float, default=0.20)
    parser.add_argument(
        "--track-buffer",
        type=int,
        default=30,
        help="Missing frames for which an ID remains recoverable.",
    )
    parser.add_argument("--output", type=Path, help="Optional annotated output video path.")
    parser.add_argument(
        "--jsonl",
        type=Path,
        help="Optional JSON Lines file containing the exact per-frame response schema.",
    )
    parser.add_argument(
        "--selection-jsonl",
        type=Path,
        help=(
            "Optional TargetSpec decision JSON Lines file. Each source frame records "
            "the selection state/count and whether that frame was analyzed. Requires "
            "--target-spec."
        ),
    )
    parser.add_argument("--max-frames", type=int, help="Stop after this many analyzed frames.")
    parser.add_argument(
        "--no-display",
        action="store_true",
        help="Disable the interactive OpenCV window for headless runs.",
    )
    return parser


def create_pipeline(args: argparse.Namespace) -> OnDeviceCVPipeline:
    if args.matching_confidence > args.confidence:
        raise ValueError("--matching-confidence cannot exceed --confidence")

    detector = UltralyticsYoloDetector(
        UltralyticsDetectorConfig(
            model=args.model,
            input_size=args.imgsz,
            confidence_threshold=args.matching_confidence,
            max_detections=args.max_detections,
            device=args.device,
        )
    )
    tracker = ByteTrackLiteTracker(
        ByteTrackLiteConfig(
            track_activation_threshold=args.confidence,
            minimum_matching_confidence=args.matching_confidence,
            first_match_iou_threshold=args.match_iou,
            second_match_iou_threshold=args.low_match_iou,
            lost_track_buffer=args.track_buffer,
        )
    )
    return OnDeviceCVPipeline(
        detector,
        tracker,
        config=PipelineConfig(output_confidence_threshold=args.confidence),
    )


def run(args: argparse.Namespace) -> int:
    if args.frame_stride <= 0:
        raise ValueError("--frame-stride must be positive")
    if args.selection_jsonl and not args.target_spec:
        raise ValueError("--selection-jsonl requires --target-spec")
    source = _parse_source(args.source)
    _validate_input_output_paths(source, args.output, args.jsonl, args.selection_jsonl)
    capture: cv2.VideoCapture | None = None
    writer: cv2.VideoWriter | None = None
    jsonl_file: TextIO | None = None
    selection_jsonl_file: TextIO | None = None
    pipeline: OnDeviceCVPipeline | None = None
    frame_count = 0
    input_frame_index = 0
    smoothed_fps: float | None = None
    last_result = FrameResult()
    display_deadline = 0.0
    target_spec = TargetSpec.from_file(args.target_spec) if args.target_spec else None
    target_selector = TargetSelector()
    target_status_text: str | None = None
    last_selection: TargetSelectionResult | None = None

    try:
        capture = cv2.VideoCapture(source)
        if not capture.isOpened():
            raise RuntimeError(f"Could not open video source: {args.source}")
        source_fps = _capture_fps(capture)
        display_interval_s = 1.0 / source_fps
        stream_started_at = time.perf_counter()
        pipeline = create_pipeline(args)
        pipeline.load()
        display_deadline = time.perf_counter()
        if args.jsonl:
            args.jsonl.parent.mkdir(parents=True, exist_ok=True)
            jsonl_file = args.jsonl.open("w", encoding="utf-8")
        if args.selection_jsonl:
            args.selection_jsonl.parent.mkdir(parents=True, exist_ok=True)
            selection_jsonl_file = args.selection_jsonl.open("w", encoding="utf-8")

        while True:
            ok, frame = capture.read()
            if not ok:
                break
            current_frame_index = input_frame_index
            should_analyze = current_frame_index % args.frame_stride == 0
            input_frame_index += 1

            if should_analyze:
                started_at = time.perf_counter()
                timestamp_s = _frame_timestamp_s(
                    source,
                    current_frame_index,
                    source_fps,
                    stream_started_at,
                )
                all_objects = pipeline.process(frame, timestamp_s=timestamp_s)
                if target_spec is not None:
                    selection = target_selector.select(all_objects, target_spec)
                    last_selection = selection
                    last_result = selection.to_frame_result()
                    requested_count = (
                        "any"
                        if selection.requested_count is None
                        else str(selection.requested_count)
                    )
                    target_status_text = (
                        f"target {selection.state.value} | "
                        f"{selection.detected_count}/{requested_count}"
                    )
                else:
                    last_result = all_objects
                elapsed = max(time.perf_counter() - started_at, 1e-9)
                instantaneous_fps = 1.0 / elapsed
                smoothed_fps = (
                    instantaneous_fps
                    if smoothed_fps is None
                    else 0.90 * smoothed_fps + 0.10 * instantaneous_fps
                )
                frame_count += 1

            annotated = draw_frame_result(
                frame,
                last_result,
                fps=smoothed_fps,
                status_text=target_status_text,
            )

            if jsonl_file is not None:
                jsonl_file.write(json.dumps(last_result.to_dict(), ensure_ascii=False) + "\n")
            if selection_jsonl_file is not None and last_selection is not None:
                selection_payload = last_selection.to_dict()
                selection_payload["frameIndex"] = current_frame_index
                selection_payload["analyzed"] = should_analyze
                selection_jsonl_file.write(json.dumps(selection_payload, ensure_ascii=False) + "\n")

            if args.output:
                if writer is None:
                    writer = _open_video_writer(
                        args.output,
                        capture,
                        annotated.shape[1],
                        annotated.shape[0],
                    )
                writer.write(annotated)

            if not args.no_display:
                cv2.imshow("Snap-Sight On-device CV", annotated)
                display_deadline += display_interval_s
                remaining_ms = math.ceil((display_deadline - time.perf_counter()) * 1000)
                key = cv2.waitKey(max(1, remaining_ms)) & 0xFF
                if key in (27, ord("q")):
                    break

            if should_analyze and args.max_frames is not None and frame_count >= args.max_frames:
                break
    finally:
        if pipeline is not None:
            pipeline.close()
        if capture is not None:
            capture.release()
        if writer is not None:
            writer.release()
        if jsonl_file is not None:
            jsonl_file.close()
        if selection_jsonl_file is not None:
            selection_jsonl_file.close()
        if not args.no_display:
            cv2.destroyAllWindows()

    return frame_count


def _open_video_writer(
    output_path: Path,
    capture: cv2.VideoCapture,
    width: int,
    height: int,
) -> cv2.VideoWriter:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_fps = _capture_fps(capture)
    writer = cv2.VideoWriter(
        str(output_path),
        cv2.VideoWriter_fourcc(*"mp4v"),
        output_fps,
        (width, height),
    )
    if not writer.isOpened():
        writer.release()
        raise RuntimeError(f"Could not create output video: {output_path}")
    return writer


def _capture_fps(capture: cv2.VideoCapture) -> float:
    source_fps = float(capture.get(cv2.CAP_PROP_FPS))
    return source_fps if 0.0 < source_fps < 1000.0 else 30.0


def _frame_timestamp_s(
    source: str | int,
    frame_index: int,
    source_fps: float,
    stream_started_at: float,
) -> float:
    """Return a monotonic stream-relative timestamp for tracker motion prediction."""

    if isinstance(source, int):
        return time.perf_counter() - stream_started_at
    return frame_index / source_fps


def _parse_source(value: str) -> str | int:
    stripped = value.strip()
    if stripped.isdecimal() or (stripped.startswith("-") and stripped[1:].isdecimal()):
        return int(stripped)
    return value


def _validate_input_output_paths(
    source: str | int,
    *output_paths: Path | None,
) -> None:
    resolved_outputs = [path.resolve() for path in output_paths if path is not None]
    if len(resolved_outputs) != len(set(resolved_outputs)):
        raise ValueError("--output, --jsonl, and --selection-jsonl paths must be distinct")
    if isinstance(source, int):
        return
    source_path = Path(source)
    if not source_path.exists():
        return
    if source_path.resolve() in resolved_outputs:
        raise ValueError("output files must not overwrite the input video")


def main(argv: list[str] | None = None) -> int:
    parser = build_argument_parser()
    args = parser.parse_args(argv)
    if args.max_frames is not None and args.max_frames <= 0:
        parser.error("--max-frames must be positive")
    if args.frame_stride <= 0:
        parser.error("--frame-stride must be positive")
    try:
        run(args)
    except (RuntimeError, TypeError, ValueError) as exc:
        parser.exit(1, f"error: {exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
