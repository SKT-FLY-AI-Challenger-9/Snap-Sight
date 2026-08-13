from pathlib import Path

import numpy as np
import pytest

from ai.on_device_cv import demo
from ai.on_device_cv.contracts import FrameResult
from ai.on_device_cv.demo import _parse_source, _validate_input_output_paths, build_argument_parser


def test_parse_source_supports_webcam_indices_and_video_paths():
    assert _parse_source("0") == 0
    assert _parse_source(" 2 ") == 2
    assert _parse_source("video.mp4") == "video.mp4"


def test_frame_stride_defaults_to_every_frame_and_accepts_sampling_interval():
    parser = build_argument_parser()

    assert parser.parse_args([]).frame_stride == 1
    assert parser.parse_args(["--frame-stride", "3"]).frame_stride == 3


def test_demo_refuses_to_overwrite_its_input_video(tmp_path):
    video_path = tmp_path / "input.mp4"
    video_path.touch()

    with pytest.raises(ValueError, match="must not overwrite"):
        _validate_input_output_paths(str(video_path), Path(video_path))


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

        def load(self):
            pass

        def process(self, frame):
            self.process_calls += 1
            return FrameResult()

        def close(self):
            pass

    capture = FakeCapture()
    pipeline = FakePipeline()
    monkeypatch.setattr(demo.cv2, "VideoCapture", lambda source: capture)
    monkeypatch.setattr(demo, "create_pipeline", lambda args: pipeline)
    jsonl_path = tmp_path / "results.jsonl"
    args = build_argument_parser().parse_args(
        [
            "--source",
            "0",
            "--frame-stride",
            "3",
            "--jsonl",
            str(jsonl_path),
            "--no-display",
        ]
    )

    analyzed_count = demo.run(args)

    assert analyzed_count == 3
    assert pipeline.process_calls == 3
    assert len(jsonl_path.read_text(encoding="utf-8").splitlines()) == 7
    assert capture.released
