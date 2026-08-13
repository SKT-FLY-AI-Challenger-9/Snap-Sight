import json
from pathlib import Path

import numpy as np
import pytest

from ai.on_device_cv import demo
from ai.on_device_cv.contracts import BoundingBox, FrameResult, TrackedObject
from ai.on_device_cv.demo import _parse_source, _validate_input_output_paths, build_argument_parser


def test_parse_source_supports_webcam_indices_and_video_paths():
    assert _parse_source("0") == 0
    assert _parse_source(" 2 ") == 2
    assert _parse_source("video.mp4") == "video.mp4"


def test_frame_stride_defaults_to_every_frame_and_accepts_sampling_interval():
    parser = build_argument_parser()

    assert parser.parse_args([]).frame_stride == 1
    assert parser.parse_args(["--frame-stride", "3"]).frame_stride == 3


def test_demo_accepts_a_target_spec_json_path():
    parser = build_argument_parser()

    args = parser.parse_args(
        [
            "--target-spec",
            "intent.json",
            "--selection-jsonl",
            "selection.jsonl",
        ]
    )

    assert args.target_spec == Path("intent.json")
    assert args.selection_jsonl == Path("selection.jsonl")


def test_selection_jsonl_requires_a_target_spec(tmp_path):
    args = build_argument_parser().parse_args(
        ["--selection-jsonl", str(tmp_path / "selection.jsonl"), "--no-display"]
    )

    with pytest.raises(ValueError, match="requires --target-spec"):
        demo.run(args)


def test_demo_refuses_to_overwrite_its_input_video(tmp_path):
    video_path = tmp_path / "input.mp4"
    video_path.touch()

    with pytest.raises(ValueError, match="must not overwrite"):
        _validate_input_output_paths(str(video_path), Path(video_path))


def test_demo_requires_distinct_artifact_paths(tmp_path):
    artifact_path = tmp_path / "results.jsonl"

    with pytest.raises(ValueError, match="must be distinct"):
        _validate_input_output_paths("0", artifact_path, artifact_path)


def test_stride_analyzes_selected_frames_but_keeps_every_frame(tmp_path, monkeypatch):
    class FakeCapture:
        def __init__(self):
            self.frames = [np.zeros((8, 8, 3), dtype=np.uint8) for _ in range(7)]
            self.index = 0
            self.released = False

        def isOpened(self):
            return True

        def read(self):
            if self.index >= len(self.frames):
                return False, None
            frame = self.frames[self.index]
            self.index += 1
            return True, frame

        def get(self, property_id):
            return 30.0

        def release(self):
            self.released = True

    class FakePipeline:
        def __init__(self):
            self.process_calls = 0
            self.timestamps = []

        def load(self):
            pass

        def process(self, frame, *, timestamp_s=None):
            self.process_calls += 1
            self.timestamps.append(timestamp_s)
            return FrameResult()

        def close(self):
            pass

    capture = FakeCapture()
    pipeline = FakePipeline()
    monkeypatch.setattr(demo.cv2, "VideoCapture", lambda source: capture)
    monkeypatch.setattr(demo, "create_pipeline", lambda args: pipeline)
    jsonl_path = tmp_path / "results.jsonl"
    selection_jsonl_path = tmp_path / "selection.jsonl"
    target_spec_path = tmp_path / "intent.json"
    target_spec_path.write_text(
        json.dumps(
            {
                "sessionId": "session-stride",
                "rawText": "사람을 찍어줘",
                "source": "ondevice",
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    args = build_argument_parser().parse_args(
        [
            "--source",
            "video.mp4",
            "--frame-stride",
            "3",
            "--target-spec",
            str(target_spec_path),
            "--jsonl",
            str(jsonl_path),
            "--selection-jsonl",
            str(selection_jsonl_path),
            "--no-display",
        ]
    )

    analyzed_count = demo.run(args)

    assert analyzed_count == 3
    assert pipeline.process_calls == 3
    assert pipeline.timestamps == pytest.approx([0.0, 0.1, 0.2])
    assert len(jsonl_path.read_text(encoding="utf-8").splitlines()) == 7
    selection_lines = [
        json.loads(line) for line in selection_jsonl_path.read_text(encoding="utf-8").splitlines()
    ]
    assert [line["frameIndex"] for line in selection_lines] == list(range(7))
    assert [line["analyzed"] for line in selection_lines] == [
        True,
        False,
        False,
        True,
        False,
        False,
        True,
    ]
    assert capture.released


def test_demo_target_spec_keeps_only_matching_candidates_in_public_jsonl(tmp_path, monkeypatch):
    class FakeCapture:
        def __init__(self):
            self.read_count = 0

        def isOpened(self):
            return True

        def read(self):
            if self.read_count:
                return False, None
            self.read_count += 1
            return True, np.zeros((8, 8, 3), dtype=np.uint8)

        def get(self, property_id):
            return 30.0

        def release(self):
            pass

    class FakePipeline:
        def load(self):
            pass

        def process(self, frame, *, timestamp_s=None):
            bbox = BoundingBox(0.1, 0.1, 0.4, 0.8)
            return FrameResult(
                (
                    TrackedObject(1, "Person", 0.9, bbox, class_id=0),
                    TrackedObject(2, "Bottle", 0.8, bbox, class_id=8),
                )
            )

        def close(self):
            pass

    monkeypatch.setattr(demo.cv2, "VideoCapture", lambda source: FakeCapture())
    monkeypatch.setattr(demo, "create_pipeline", lambda args: FakePipeline())
    target_spec_path = tmp_path / "intent.json"
    target_spec_path.write_text(
        """{
          "schemaVersion": "0.1",
          "sessionId": "session-1",
          "status": "ok",
          "subjectType": "person",
          "subjectCount": 1,
          "framing": "full_body",
          "rawText": "사람 한 명을 찍어줘",
          "confidence": 0.9,
          "source": "ondevice"
        }""",
        encoding="utf-8",
    )
    jsonl_path = tmp_path / "targeted.jsonl"
    selection_jsonl_path = tmp_path / "selection.jsonl"
    args = build_argument_parser().parse_args(
        [
            "--source",
            "0",
            "--target-spec",
            str(target_spec_path),
            "--jsonl",
            str(jsonl_path),
            "--selection-jsonl",
            str(selection_jsonl_path),
            "--no-display",
        ]
    )

    assert demo.run(args) == 1
    assert '"label": "Person"' in jsonl_path.read_text(encoding="utf-8")
    assert '"Bottle"' not in jsonl_path.read_text(encoding="utf-8")
    selection_payload = json.loads(selection_jsonl_path.read_text(encoding="utf-8"))
    assert selection_payload["state"] == "selected"
    assert selection_payload["countStatus"] == "exact"
    assert selection_payload["frameIndex"] == 0
    assert selection_payload["analyzed"] is True
