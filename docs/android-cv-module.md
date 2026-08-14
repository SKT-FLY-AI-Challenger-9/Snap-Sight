# ② 온디바이스 CV — Android 통합 가이드

> 담당: ② 온디바이스 CV · 위치: `frontend/app/src/main/java/com/example/snap_sight/cv/`
> PC 참조 구현: [`ai/on_device_cv`](../ai/on_device_cv/README.md)

카메라 프레임을 받아 **모든 지원 객체를 탐지·추적하고**, 같은 객체에 스트림 내내 유지되는
`track_id` 를 붙여 프레임마다 아래 JSON을 내보낸다.

```json
{
  "objects": [
    {
      "track_id": 17,
      "label": "person",
      "confidence": 0.94,
      "bbox": { "x_min": 0.31, "y_min": 0.12, "x_max": 0.68, "y_max": 0.91 }
    }
  ]
}
```

`bbox` 는 **회전 보정이 끝난 원본 프레임 기준**, 좌측 상단 원점의 normalized `xyxy` 이며 모든
좌표가 `[0, 1]` 이다. 화면 해상도·렌즈·기기가 달라도 소비자 코드는 바뀌지 않는다.

## 구조

```
cv/
├── Contracts.kt                BoundingBox / Detection / TrackedObject / FrameResult + JSON
├── Detector.kt                 CvFrame, Detector, DetectionExtension, NoOpDetector
├── TfLiteYoloDetector.kt       ← TFLite API 에 의존하는 유일한 파일 (로드·letterbox·양자화·추론)
├── InputTensorSpec.kt          입력 채널 배치 판별 (NHWC / NCHW)
├── YoloOutputDecoder.kt        출력 텐서 해석 (런타임 무관, 단위 테스트 대상)
├── Tracker.kt                  tracker 계약
├── ByteTrackLiteTracker.kt     ByteTrack-lite Kotlin 포팅 (2단계 association)
├── CvPipeline.kt               detect → extensions → track → threshold
├── YuvToRgbConverter.kt        YUV_420_888 → 회전 보정 upright RGB888
├── TargetSpec.kt               ① 의도 스펙 (파싱·보관만, 해석 안 함)
├── TargetSelector.kt           의도 기반 후보 선택 확장 자리
├── Deviation.kt                편차 계산 확장 자리
├── CvOutput.kt                 CvFrameOutput / ObjectStreamListener
├── SnapSightFrameProcessor.kt  CameraX 진입점 (FrameProcessor 구현)
├── FrameProcessor.kt           ⑤가 정의한 프레임 수신 계약
└── LoggingFrameProcessor.kt    ⑤의 파이프라인 검증용 더미 (FPS 로그)
```

처리 순서:

```
ImageProxy (YUV_420_888, rotationDegrees)
  → YuvToRgbConverter        색 변환 + 회전 + (선택) 다운샘플을 한 번의 순회로
  → CvFrame                  upright RGB888
  → TfLiteYoloDetector       letterbox/quantize/decode/NMS 를 전부 내부에서 처리
  → Detection[]              normalized xyxy 공통 계약
  → DetectionExtension[]     향후 person → face 확장 지점
  → ByteTrackLiteTracker     다중 객체 ID 연결
  → 출력 confidence threshold
  → TargetSelector           (현재 pass-through)
  → DeviationCalculator      (현재 no-op)
  → CvFrameOutput            objectsJson() = 위 공개 계약
```

## 연결 방법

```kotlin
// listener 뒤에 설정 파라미터가 더 있으므로 trailing lambda 가 아니라 이름 붙여 넘긴다.
val cv = SnapSightFrameProcessor.create(context, listener = { output ->
    // 분석 스레드에서 호출된다. 오래 걸리면 다음 프레임이 드롭된다.
    Log.d("SnapSightCV", output.objectsJson())
})
cameraController.setFrameProcessor(cv)

// 새 촬영 세션 시작 시 — track_id 가 이전 세션과 섞이지 않도록
cv.startNewSession(spec = null)
```

`MainActivity` 에 이미 배선돼 있고, `CaptureSessionManager` 가 `AIMING` 으로 들어갈 때
`startNewSession()` 이 호출된다.

## 의도(TargetSpec)를 아직 쓰지 않는 이유와 확장 방법

CV 는 지금 의도를 **파싱·보관·전달만** 하고 해석하지 않는다. 이유는 두 가지다.

- ① STT/NLU 연동이 아직 없다. 없는 입력을 가정하고 선택 로직을 넣으면 검증할 수 없다.
- 의도가 **항상 있을 수 없다.** 마이크 권한이 없거나 발화를 건너뛴 세션에서는 의도 자체가
  존재하지 않는다 (`CaptureSessionManager.startSession()` 참고). null 이 정상 경로다.

그래서 진입점을 null-safe 하게 고정해 두었다.

```kotlin
cv.setTargetSpec(null)                 // 의도 없는 세션 — 정상
cv.setTargetSpecJson(sttResponseJson)  // 깨진 payload 도 예외 없이 무시하고 null 로
```

`TargetSpec.fromJsonOrNull(json, onError)` 는 null·빈 문자열·스키마 위반을 전부 삼키고 null 을
돌려준다. 발화 파싱이 실패했다고 카메라 루프가 멈추면 안 되기 때문이다.

붙일 때 할 일은 **selector 구현 하나 교체**가 전부다.

```kotlin
class Objects365TargetSelector : TargetSelector {
    override fun select(frameResult: FrameResult, spec: TargetSpec?): TargetSelection { … }
}

SnapSightFrameProcessor.create(context, listener, selector = Objects365TargetSelector())
```

규칙은 `ai/target_spec_schema.md` 의 "On-device CV 적용 규칙" 과
`ai/on_device_cv/target_selection.py` 에 이미 확정돼 있으므로 그대로 포팅하면 된다.
**선택은 반드시 tracking 뒤에 적용한다.** 앞에 두면 세션 중 의도가 바뀔 때마다 이미 추적 중인
객체의 `track_id` 가 새로 발급된다.

## 편차 계산 확장 자리

리드미 원안은 ③ 백엔드가 편차를 계산하지만, 매 프레임 bbox 를 서버까지 왕복시키면 햅틱
피드백 지연을 감당할 수 없다. 그래서 편차는 CV 와 같은 온디바이스 프로세스에 두되,
파이프라인과 분리된 모듈로 유지한다.

```kotlin
class CenteringDeviationCalculator : DeviationCalculator {
    override fun compute(selection: TargetSelection, spec: TargetSpec?): FramingDeviation? { … }
}

SnapSightFrameProcessor.create(context, listener, deviationCalculator = CenteringDeviationCalculator())
```

계산에 필요한 기하 원시값은 이미 계약에 있다 — `BoundingBox.centerX/centerY/area`.
결과는 `CvFrameOutput.deviation` 으로 실려 나가며, **`objects` 스키마는 건드리지 않는다.**
MLLM/TTS 쪽에서 편차를 미리 받아야 하면 이 필드를 직렬화해서 붙이면 되고, 기존 소비자는
영향을 받지 않는다.

## 모델 자산

`frontend/app/src/main/assets/` 에 두 파일이 필요하다.

| 파일 | 생성 방법 |
| --- | --- |
| `objects365_yolo26_v1_labels.txt` | `python -m ai.tools.export_tflite --labels-only` (의존성 불필요, **생성 완료**) |
| `objects365_yolo26_v1.tflite` | `python -m ai.tools.export_tflite` — **Linux/macOS 필요**, 아래 참고 |

### ⚠️ Windows 에서는 TFLite export 가 안 된다

ultralytics 의 TFLite/LiteRT 변환은 onnx2tf 툴체인에 의존하고, 이 툴체인이 Windows 를
지원하지 않는다 (`LiteRT export only supported on Linux x86 and macOS`). 패키지를 더 깐다고
해결되지 않는다. export 스크립트가 이 경우를 감지해 우회 경로를 안내한다.

- **WSL(Ubuntu)** 에서 실행 — 저장소는 `/mnt/c/Users/…/Snap-Sight` 로 보인다.
- **Google Colab** 등 리눅스 런타임에서 `.pt` 를 올려 export 하고 `.tflite` 만 내려받아 배치.

WSL 경로에서 실제로 걸린 함정 세 가지:

1. **Python 버전** — Ubuntu 26.04 의 기본 Python 은 3.14 인데 TensorFlow/PyTorch 휠이 아직
   없다. apt 에 3.12/3.11 도 없으므로 `uv` 로 standalone Python 을 깐다.
   ```bash
   curl -LsSf https://astral.sh/uv/install.sh | sh
   uv python install 3.12
   uv venv --python 3.12 ~/snapsight-export
   ```
2. **`/tmp` 가 tmpfs (약 4GB)** — 휠 추출 중 `No space left on device` 로 죽는다.
   루트 파일시스템에는 수백 GB 가 남아 있어도 그렇다. 반드시 `TMPDIR` 을 디스크로 돌린다.
   ```bash
   export TMPDIR=$HOME/tmp && mkdir -p "$TMPDIR"
   ```
3. **CUDA 휠** — ultralytics 8.4.x 의 LiteRT export 는 `litert-torch` 를 자동 설치하는데,
   기본 인덱스에서는 CUDA 포함 torch + `nvidia-*` 패키지를 수 GB 끌고 온다. CPU 인덱스로
   미리 깔아두면 ultralytics 가 자동 설치를 건너뛴다.
   ```bash
   uv pip install --python ~/snapsight-export/bin/python \
     torch torchvision litert-torch \
     --index-url https://download.pytorch.org/whl/cpu \
     --extra-index-url https://pypi.org/simple
   uv pip install --python ~/snapsight-export/bin/python ultralytics tensorflow
   ```

그 다음 저장소 루트에서:
```bash
cd /mnt/c/Users/…/Snap-Sight
TMPDIR=$HOME/tmp ~/snapsight-export/bin/python -m ai.tools.export_tflite
```

**Android 쪽 코드는 손댈 필요가 없다. 필요한 건 `.tflite` 파일 하나뿐이다.**

### 텐서 형태 (실측 확인됨)

ultralytics 8.4.120 / LiteRT-Torch 경로로 export 한 실제 모델은 다음과 같다.

```
입력 [1, 3, 640, 640] float32   → InputTensorLayout.NCHW
출력 [1, 300, 6]      float32   → YoloOutputLayout.END_TO_END
파일 11.3 MB (APK 에 비압축 패키징)
```

**입력이 NCHW 다.** ultralytics 8.4.x 의 LiteRT-Torch 경로는 PyTorch 레이아웃을 그대로
유지하기 때문이다(예전 onnx2tf 경로는 NHWC 로 바꿨다). 채널 배치를 틀리면 예외가 아니라
**색이 뒤섞인 입력**이 들어가 검출만 조용히 망가지므로, [InputTensorSpec] 이 shape 로
판별하고 두 레이아웃을 stride 한 쌍으로 통일해 처리한다. NCHW 는 채널이 평면으로 떨어져
있어 픽셀당 3개를 연속으로 쓸 수 없다 — 절대 인덱스 put 으로 한 번에 순회한다.

출력은 YOLO26 이 NMS-free end-to-end head 이므로 `YoloOutputLayout.END_TO_END`
(= `xyxy + confidence + classId`, 300개 고정 슬롯) 다.

- 이 layout 에서는 **모델이 이미 중복을 제거했으므로 앱에서 NMS 를 다시 돌리지 않는다.**
  돌리면 겹쳐 선 같은 클래스 객체(예: 나란한 사람 둘)를 정당한 검출인데도 지운다.
- 고정 슬롯이라 미사용 행은 0 으로 채워진다. 그래서 좌표 단위 판별은 **실제 검출이 있는
  행에서만** 수행하고, 검출이 없는 프레임에서는 판별을 미룬다. 검출 0개인 첫 프레임으로
  단위를 확정해버리면 이후 모든 bbox 가 조용히 틀어진다.
- 모델을 v8 계열로 교체하면 `[1, 4+nc, anchors]` / `[1, anchors, 4+nc]` 도 자동 판별되고,
  그때는 class 별 NMS 가 켜진다.

### 기타

- export 전에 checkpoint 의 `model.names` 를 `ai/taxonomy/objects365_yolo26_v1.json` 과
  class ID 순서까지 대조한다. 어긋나면 아무것도 만들지 않고 실패한다 — 한 칸만 밀려도
  detector 가 조용히 엉뚱한 label 을 붙이기 때문이다. (현재 체크포인트는 365개 순서 일치 확인됨)
- 라벨 파일은 **줄 번호 = class ID** 다. 정렬하거나 빈 줄을 넣으면 매핑이 깨진다.
- 좌표 단위(정규화 vs 입력 픽셀)는 첫 유효 추론 결과에서 자동 판별하고 로그에 남긴다.

**자산이 없어도 앱은 죽지 않는다.** 로드 실패를 `SnapSightFrameProcessor` 가 잡아
"검출 0개" 로 계속 돌기 때문에 카메라·세션·업로드 경로는 그대로 검증할 수 있다.
실패 사유는 `tag:SnapSightCV` 로그에 남는다.

## 성능 조절

```kotlin
SnapSightFrameProcessor.create(
    context, listener,
    config = FrameProcessorConfig(
        analyzeEveryNthFrame = 2,     // 2프레임마다 1번만 추론
        maxAnalysisDimension = 640,   // YUV→RGB 변환 전에 긴 변을 640 이하로 솎아냄
    ),
    detectorConfig = TfLiteDetectorConfig(numThreads = 4, maxDetections = 300),
)
```

- 건너뛴 프레임도 직전 결과를 `analyzed = false` 로 내보낸다(`emitHeldResults`). ⑥ 피드백
  루프가 끊기지 않게 하기 위해서다. 실제로 새로 계산된 값만 필요하면 `analyzed` 로 거른다.
- `TfLiteDetectorConfig.minimumConfidence` 는 tracker 의 `minimumMatchingConfidence` 와
  맞춰야 한다. detector 에서 미리 잘라내면 저신뢰 검출을 이용한 ID 복구가 동작하지 않는다.
  둘이 어긋나면 `create()` 가 경고 로그를 남긴다.

## 검증

모델 없이 JVM 에서 도는 단위 테스트:

```powershell
cd frontend
.\gradlew.bat :app:testDebugUnitTest
```

| 파일 | 검증 대상 |
| --- | --- |
| `ContractsTest.kt` | JSON 스키마 고정, bbox 정규화·clipping, 중복 track_id 거부 |
| `ByteTrackLiteTrackerTest.kt` | ID 연속성, 입력 순서 변경, 저신뢰 복구, 일시 가림, 만료 후 새 ID, label flicker, reset, timestamp 계약 |
| `CvPipelineTest.kt` | 출력 threshold, extension 삽입, reset, 로드 멱등성, 모델 부재 |
| `TargetSpecTest.kt` | 의도 null·깨진 payload 안전 처리, v0.1/v0.2 파싱, pass-through selector |
| `YoloOutputDecoderTest.kt` | layout 판별, letterbox 역변환, 좌표 단위(정규화/픽셀) 판별, 검출 0개 프레임에서 오판 방지, e2e 중복 보존, 범위 밖 class ID·비정상 박스 처리 |
| `InputTensorSpecTest.kt` | NHWC/NCHW 판별, 채널 인덱싱(인터리브 vs 평면), 전 요소 중복·누락 없음 |

`YoloOutputDecoder` 를 `TfLiteYoloDetector` 에서 분리한 이유가 이것이다 — 출력 해석은
틀려도 예외가 안 나고 조용히 틀리므로, 실기기·모델 없이 JVM 에서 고정할 수 있어야 한다.

## PC 구현과의 차이

| 항목 | PC (`ai/on_device_cv`) | Android (`cv/`) |
| --- | --- | --- |
| 프레임 | OpenCV BGR `ndarray` | `CvFrame` (upright RGB888) |
| detector | Ultralytics PyTorch `.pt` | TFLite `.tflite` |
| 좌표 | float64 | 계약은 Float, tracker 내부 연산은 Double |
| 할당 solver | NumPy + 자체 JV 구현 | 같은 알고리즘의 순수 Kotlin 포팅 |
| 의도 선택 | `TargetSelector` 구현 완료 | 확장 자리만 (pass-through) |

association 의미와 임계값 기본값은 양쪽이 같다. 한쪽을 고치면 반드시 다른 쪽도 고친다.

## 알려진 한계

- IoU/motion 기반 tracker 라 비슷한 객체가 완전히 교차하거나, 카메라가 급격히 움직이거나,
  객체가 오래 가려지면 ID switch 가 생길 수 있다. 구조상 full ByteTrack / BoT-SORT / ReID 로
  교체 가능하다.
- 실기기 latency(p50/p95)는 아직 측정하지 않았다. `analyzeEveryNthFrame`,
  `maxAnalysisDimension`, `maxDetections`, `numThreads` 로 조절한다.
- Ultralytics 코드와 checkpoint 는 AGPL-3.0 또는 Enterprise 조건이다. Objects365 데이터
  이용 조건과 함께 배포 전 검토가 필요하다.
