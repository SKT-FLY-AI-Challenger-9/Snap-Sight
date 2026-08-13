# Snap-Sight On-device CV prototype

카메라 또는 영상의 모든 지원 객체를 프레임마다 탐지하고, 같은 객체에 지속적인
`track_id`를 부여하는 PC용 Python 프로토타입입니다. STT/NLU 파싱 자체는 포함하지 않지만,
이미 생성된 TargetSpec v0.1을 입력받아 tracking 후 의도 후보를 선택할 수 있습니다.

기본 detector는 Ultralytics가 공개한 Objects365v1 365-class nano checkpoint
`yolo26n-objv1-150.pt`입니다. `classes=None`으로 추론하므로 특정 클래스만 고르지 않고
모델이 지원하는 전체 클래스를 탐지합니다. 공식 모델 정보와 정확도/크기는
[Ultralytics Objects365 문서](https://docs.ultralytics.com/datasets/detect/objects365/)에서
확인할 수 있습니다.

## 구조

```text
OpenCV BGR frame
  -> UltralyticsYoloDetector       모델 resize/NMS/raw tensor 격리
  -> DetectionResult[]             normalized xyxy 공통 계약
  -> DetectionExtension[]          향후 person -> face detector 확장 지점
  -> ByteTrackLiteTracker          다중 객체 ID 연결
  -> output confidence threshold
  -> FrameResult / dict            공개 JSON 계약
```

- `contracts.py`: `BoundingBox`, `DetectionResult`, `TrackedObject`, `FrameResult`
- `detectors/base.py`: detector protocol과 공통 설정
- `detectors/ultralytics_yolo.py`: 현재 모델 의존 코드
- `trackers/base.py`: tracker protocol
- `trackers/byte_track_lite.py`: NumPy 기반 portable tracker
- `extensions.py`: 향후 Face Detector를 삽입할 hook
- `pipeline.py`: 모델과 무관한 프레임 처리 순서
- `visualization.py`, `demo.py`: PC 영상/웹캠 데모

`ByteTrackLiteTracker`는 고신뢰 detection으로 새 track을 만들고, 저신뢰 detection은
기존 track 복구에만 사용하는 2단계 association을 수행합니다. 상수속도 bbox 예측,
최적 IoU 할당, 일시적인 detection 누락 버퍼, confidence-weighted label voting을
포함합니다. upstream ByteTrack의 Kalman filter 구현을 복사한 것은 아니며, Android로
옮기기 쉬운 NumPy reference입니다.

## 설치와 실행

저장소 루트에서 가상환경을 만든 뒤 의존성을 설치합니다.

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
```

영상 파일:

```powershell
python -m ai.on_device_cv --source .\sample.mp4
```

기본 웹캠:

```powershell
python -m ai.on_device_cv --source 0
```

첫 실행에서는 Ultralytics가 공식 checkpoint를 내려받습니다. 화면에는 각 객체의
bbox와 `#track_id label confidence`가 표시됩니다. `q` 또는 `Esc`로 종료합니다.

annotated video와 프레임별 JSON Lines를 함께 저장하려면:

```powershell
python -m ai.on_device_cv `
  --source .\sample.mp4 `
  --output .\demo_outputs\tracked.mp4 `
  --jsonl .\demo_outputs\tracked.jsonl
```

GUI가 없는 환경에서는 `--no-display`를 추가합니다. 주요 옵션은 다음과 같습니다.

- `--confidence 0.25`: 새 track 생성 및 공개 결과의 confidence threshold
- `--matching-confidence 0.10`: 기존 ID 복구용 detector 최저 threshold
- `--frame-stride 3`: 원본 영상의 매 3번째 프레임만 detector/tracker로 분석
- `--track-buffer 30`: detection이 누락되어도 ID를 내부 보존할 프레임 수
- `--imgsz 640`, `--max-detections 300`, `--device cpu`
- `--model <name-or-path>`: detector checkpoint 교체

`matching-confidence` 이상이고 `confidence` 미만인 객체는 새 ID를 만들거나 공개
결과에 나타나지 않지만 기존 track과 연결되어 다음 고신뢰 프레임의 ID 연속성을
높입니다.

예를 들어 다음 명령은 원본 프레임 `0, 3, 6, ...`에서만 detector/tracker를 실행합니다.
분석하지 않는 중간 프레임도 모두 화면과 저장 영상에 포함되며, 가장 최근 bbox 결과를
그대로 표시합니다. 따라서 bbox는 세 프레임마다 한 번 갱신됩니다. JSONL에도 원본
프레임마다 한 줄이 기록되며 중간 프레임에는 직전 분석 결과가 반복됩니다.
`--max-frames`와 `--track-buffer`는 분석 프레임 개수를 기준으로 동작합니다.

```powershell
python -m ai.on_device_cv --source .\sample.mp4 --frame-stride 3
```

## TargetSpec 기반 의도 후보 선택

① STT/NLU가 `ai/target_spec_schema.md` 형식의 JSON을 만들었다면 `--target-spec`으로
전달할 수 있습니다. 저장소의 `ai/on_device_cv/examples/person_two.json`을 바로 사용하거나,
예를 들어 `intent.json`을 다음과 같이 작성합니다.

```json
{
  "schemaVersion": "0.1",
  "sessionId": "sess_20260812_001",
  "status": "ok",
  "subjectType": "person",
  "subjectCount": 2,
  "framing": "closeup",
  "rawText": "친구 두 명이랑 같이 나오게, 얼굴 크게 찍어줘",
  "confidence": 0.9,
  "source": "clova"
}
```

```powershell
python -m ai.on_device_cv `
  --source .\sample.mp4 `
  --target-spec .\intent.json `
  --jsonl .\demo_outputs\target_objects.jsonl `
  --selection-jsonl .\demo_outputs\target_selection.jsonl
```

detector와 tracker는 계속 Objects365 전체 객체를 처리하고, 화면과 JSONL에는 TargetSpec과
일치하는 후보만 표시됩니다. 이 선택은 tracking 뒤에 적용되므로 실행 중 의도가 바뀌어도
기존 객체의 `track_id`가 새로 발급되지 않습니다. 화면 왼쪽 위에는
`target selected|searching|ambiguous|scene_only|unresolved` 상태와 현재 후보 수가 함께
표시됩니다. 기존 JSONL은 호환성을 위해 `{"objects": [...]}` 형식을 그대로 유지합니다.
`--selection-jsonl`에는 `selected|searching|ambiguous|scene_only|unresolved`, 요청/검출 개수,
후보 객체가 versioned `schemaVersion=0.1` 형식으로 함께 기록되므로 기계 판정에는 이 파일을
사용합니다. 두 JSONL 모두 원본 입력
프레임마다 한 줄을 기록합니다. `--frame-stride N`의 중간 프레임에는 최근 결과가 반복되고,
selection JSONL의 `analyzed=false`로 미분석 프레임을 구분할 수 있습니다.

- `person`: 사람 후보만 표시
- `object`: 사람을 제외한 모든 사물 후보 표시
- `landscape`: 객체 target을 만들지 않음
- 요청 개수보다 후보가 적으면 `searching`, 같으면 `selected`, 많으면 `ambiguous`

v0.1에는 구체 사물 class를 나타내는 필드가 없으므로 "컵"과 "의자"를 구분해 선택할 수는
없습니다. 또한 `framing`은 현재 detection filtering이 아니라 후속 구도 판단용 값입니다.

Python에서는 전체 tracking 결과와 target 후보를 모두 유지할 수 있습니다.

```python
from ai.on_device_cv.target_selection import TargetSelector
from ai.target_spec import TargetSpec

target_spec = TargetSpec.from_file("intent.json")
all_objects = pipeline.process(frame_bgr, timestamp_s=frame_timestamp_s)
selection = TargetSelector().select(all_objects, target_spec)

candidate_payload = selection.to_dict()  # state/count 상태 포함
existing_payload = selection.to_frame_result().to_dict()  # 기존 objects schema
```

## Python API와 출력 계약

```python
from ai.on_device_cv.detectors import UltralyticsDetectorConfig, UltralyticsYoloDetector
from ai.on_device_cv.pipeline import OnDeviceCVPipeline, PipelineConfig
from ai.on_device_cv.trackers import ByteTrackLiteTracker

detector = UltralyticsYoloDetector(UltralyticsDetectorConfig())
pipeline = OnDeviceCVPipeline(
    detector,
    ByteTrackLiteTracker(),
    config=PipelineConfig(output_confidence_threshold=0.25),
)

with pipeline:
    frame_result = pipeline.process(frame_bgr, timestamp_s=frame_timestamp_s)
    payload = frame_result.to_dict()
```

`payload`는 항상 다음 형태입니다. bbox는 원본 upright frame 기준, 좌측 상단 원점의
normalized `xyxy`이며 모든 좌표는 `[0, 1]`입니다.

```json
{
  "objects": [
    {
      "track_id": 17,
      "label": "person",
      "confidence": 0.94,
      "bbox": {
        "x_min": 0.31,
        "y_min": 0.12,
        "x_max": 0.68,
        "y_max": 0.91
      }
    }
  ]
}
```

새 영상/카메라 세션을 시작할 때 `pipeline.reset()`을 호출하면 이전 track 상태가
섞이지 않고 ID가 다시 1부터 시작합니다.

PC prototype은 Python 3.11 이상을 기준으로 합니다. 현재는 저장소 루트에서 module로
실행하며 별도 wheel/console entry point는 제공하지 않습니다.

## 모델과 Face Detector 교체 지점

Objects365 subset으로 fine-tuning한 모델은 pipeline 수정 없이 바꿀 수 있습니다.

```powershell
python -m ai.on_device_cv --source .\sample.mp4 --model .\models\best.pt
```

다른 runtime을 쓸 때는 `Detector` protocol의 `load/detect/close`만 구현합니다. 색상
변환, letterbox, quantization, raw tensor decode, NMS, class map은 adapter 안에서 끝내고
외부에는 `DetectionResult`만 반환해야 합니다.

향후 face 기능은 `DetectionExtension`을 구현해 pipeline의 `extensions`에 넣습니다.
구현체는 primary detection 중 `person` bbox만 crop하고, face box를 원본 frame의
normalized 좌표로 다시 매핑한 `DetectionResult(label="face", ...)`를 반환하면 됩니다.
pipeline과 tracker의 공개 출력 계약은 바뀌지 않습니다.

Android/TFLite 이식 시에도 다음 경계를 유지합니다.

- `UltralyticsYoloDetector`만 TFLite adapter로 교체
- `DetectionResult`와 normalized bbox 계약 유지
- tracker의 상태/association을 Kotlin으로 포팅하거나 `Tracker` 구현 교체
- CameraX `ImageAnalysis`와 UI overlay는 pipeline 바깥에서 처리

## 검증과 한계

모델 다운로드 없이 실행되는 단위 테스트:

```powershell
python -m pytest tests\test_cv_contracts.py tests\test_cv_tracker.py `
  tests\test_cv_demo.py tests\test_cv_pipeline.py tests\test_cv_visualization.py `
  tests\test_ultralytics_detector.py tests\test_target_spec.py `
  tests\test_target_selection.py
```

테스트는 정확한 JSON schema, bbox 정규화, 다중 객체, 입력 순서 변경, 저신뢰 복구,
일시 가림, 만료 후 새 ID, label flicker, stream reset, detector raw output 격리를
검증합니다.

개발 시 공식 모델 smoke test는 다음처럼 실행할 수 있습니다. 첫 실행은 checkpoint
다운로드를 위해 네트워크가 필요합니다.

```powershell
python -m ai.on_device_cv `
  --source .\sample.mp4 `
  --no-display `
  --max-frames 5 `
  --output .\demo_outputs\smoke.mp4 `
  --jsonl .\demo_outputs\smoke.jsonl
```

IoU/motion 기반 tracker이므로 서로 비슷한 객체가 완전히 교차하거나, 카메라가 급격히
움직이거나, 객체가 오래 가려지는 상황에서는 ID switch가 생길 수 있습니다. 구조상
향후 full ByteTrack, BoT-SORT, ReID tracker로 교체할 수 있습니다. 누락 프레임의 예측
bbox는 ID 복구를 위해 내부에만 보존하며 confidence 의미가 불명확하므로 JSON에는
출력하지 않습니다.

association은 객체 수에 따라 비용이 증가하므로 `--max-detections 300`은 정확도 우선
상한입니다. 매우 혼잡한 프레임에서는 tracker도 병목이 될 수 있습니다. 대상 PC/폰
영상으로 p50/p95 latency를 측정하고, 필요하면 이 값을 낮추거나 vectorized/compiled
assignment를 구현해야 합니다. `timestamp_s`를 생략하면 한 번의 `process()` 호출을
한 시간 단위로 봅니다. Android CameraX가 분석 프레임을 불규칙하게 drop할 수 있으므로
해당 stream의 모든 호출에 camera timestamp(초)를 전달하는 것을 권장합니다. 한 stream
안에서는 timestamp 사용 여부를 섞을 수 없고 값은 단조 증가해야 합니다.

Ultralytics 코드와 checkpoint는 기본적으로 AGPL-3.0 또는 Enterprise 조건입니다.
Objects365 데이터 이용 조건도 제품 배포 전에 별도로 검토해야 합니다. 이 구현을
상용 Android 앱에 포함하기 전 라이선스를 확정하세요.
