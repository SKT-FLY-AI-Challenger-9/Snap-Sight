# 온디바이스 탐지 모듈 (⑤×② — YOLO26n on TFLite)

`cv/` 패키지에서 CameraX 분석 스트림 위에 YOLO26n TFLite 추론을 돌린다.
모델 파일이 없으면 자동으로 fps 로거(`LoggingFrameProcessor`)로 폴백하므로,
모델 없이도 앱 빌드·세션 흐름은 그대로 동작한다.

## 구조

| 파일 | 역할 |
| --- | --- |
| `cv/FrameProcessor.kt` | 분석 스트림 계약 (기존, 변경 없음) |
| `cv/YoloFrameProcessor.kt` | TFLite 인터프리터 로드·전처리(letterbox)·추론·fps/지연 로그 |
| `cv/YoloPostprocessor.kt` | 출력 텐서 → `Detection` 변환 (순수 로직, JVM 테스트 대상) |
| `cv/Detection.kt` | 탐지 결과 모델 — 정방향 프레임 기준 0..1 정규화 bbox |

데이터 흐름:

```
ImageProxy(YUV) → JPEG → Bitmap(회전 보정) → letterbox 320×320 → float RGB
  → Interpreter.run → [1, N, 6] (x1,y1,x2,y2,score,cls)
  → YoloPostprocessor.decode → List<Detection> (0..1 정규화)
  → DetectionListener.onDetections  ← ③ 판정 로직 연동 지점
```

## 모델 파일

`frontend/app/src/main/assets/models/yolo26n.tflite` (fp32, ~9.4MB) 가 레포에 포함되어 있다.
입력 `[1,320,320,3]` float32, 출력 `[1,300,6]` (x1,y1,x2,y2,score,cls — **0..1 정규화 좌표**).

### 재생성 방법 (모델 교체 시)

LiteRT export 는 **Linux/macOS 전용**이다. Windows 에서는 WSL 에서 실행:

```bash
# ultralytics 8.4.x 의 litert_torch 경로는 torch 버전 충돌이 있어 8.3.x 고정.
# 변환 스택을 한 번에 설치해야 numpy/scipy 버전이 꼬이지 않는다.
uv venv --python 3.11 .venv && source .venv/bin/activate
uv pip install "ultralytics>=8.3.200,<8.4" "tensorflow>=2.16,<2.20" tf_keras \
  "onnx>=1.12,<1.18" onnx2tf onnxslim sng4onnx onnx_graphsurgeon \
  ai-edge-litert "numpy<2.3" scipy

yolo export model=yolo26n.pt format=tflite imgsz=320 nms=True
cp yolo26n_saved_model/yolo26n_float32.tflite \
  frontend/app/src/main/assets/models/yolo26n.tflite
```

- **`nms=True` 필수** — 없으면 raw `[1,84,2100]` 출력이 나오고, 로드 시
  "지원하지 않는 출력 형태" 에러와 함께 탐지가 비활성화된다.
- `imgsz` 는 320 이 아니어도 됨 (입력 크기는 모델에서 읽어 자동 적용).
- 좌표 단위(픽셀/정규화)는 후처리에서 자동 감지하므로 export 버전이 달라져도 안전.
- `.tflite` 는 `noCompress` 처리되어 있어 assets 에서 메모리 매핑으로 로드된다.
- 용량이 부담되면 `yolo26n_float16.tflite`(~5MB) 로 교체 가능 (입출력은 동일하게 float32).

## 좌표계 계약 (② #2 와 합의 필요)

- 회전 보정된 **정방향 프레임 기준, 0.0~1.0 정규화** left/top/right/bottom.
- 클래스명은 `ai/target_spec_schema.md` objectLabel 과 동일한 snake_case COCO 명.
- `Detection.centerX / areaRatio` 를 ③ 편차·거리 판정의 입력값으로 제안한다
  (→ `docs/detection-api-design.md`).

## 검증

- 단위 테스트: `YoloPostprocessorTest` — letterbox 역변환, 임계값·정렬, 스키마 라벨 정합.
- 실기기/에뮬레이터: Logcat `tag:SnapSightYolo` 로 fps·평균 추론 지연·top 탐지 확인.

## 알려진 한계 (후속)

- 전처리에 JPEG 인코딩/디코딩을 경유(기존 링 버퍼 유틸 재사용) — 프레임당 수~십 ms 추가.
  성능이 부족하면 YUV→RGB 직접 변환으로 교체한다.
- GPU/NNAPI delegate 미적용 (CPU 4스레드). 실기기 fps 기준선 측정 후 결정.
