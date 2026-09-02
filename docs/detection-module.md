# 온디바이스 탐지 모듈 (아카이브)

> **이 문서가 설명하던 COCO YOLO26n 단독 탐지 경로는 제거되었다.**
> 현재 온디바이스 탐지·추적 파이프라인은 Objects365 기반 `cv/SnapSightFrameProcessor`
> (PR #20)이며, 정식 가이드는 **`docs/android-cv-module.md`** 를 본다.
> 탐지 박스 디버그 오버레이는 `ux/DetectionOverlay.kt` (TrackedObject 스트림 기반)로 유지된다.

아래는 모델을 TFLite 로 다시 export 할 때 재발 방지를 위해 남기는 노하우다.

## YOLO26 계열 TFLite export 주의사항

- **ultralytics 8.4.120 이상을 사용할 것.** 8.3.x 는 YOLO26 아키텍처를 잘못 조립해
  겉보기엔 변환이 성공하지만 탐지 품질이 심하게 손상된다
  (표준 테스트 이미지 bus.jpg 에서 사람·버스를 못 잡고 오탐 발생 — 실측 확인됨).
- LiteRT export 는 **Linux/macOS 전용** — Windows 에서는 WSL 에서 실행한다.

```bash
uv venv --python 3.11 .venv && source .venv/bin/activate
uv pip install "ultralytics>=8.4.120" litert-torch onnx
yolo export model=<model>.pt format=tflite imgsz=<size>
```

- 8.4 litert 경로의 출력물은 입력이 **NCHW `[1,3,S,S]`**, 좌표는 **입력 픽셀 단위**다
  (8.3 구경로는 NHWC·정규화 좌표 — export 버전에 따라 달라지므로 소비 코드에서 확인 필요).

## 교체 전 품질 게이트 (필수)

새로 export 한 모델은 탑재 전에 표준 이미지로 최소 품질을 확인한다:

```bash
yolo predict model=<exported>.tflite source=bus.jpg imgsz=<size> conf=0.35
# 기대: 4 persons, 1 bus — 엉뚱한 클래스가 상위에 오면 변환 손상을 의심할 것
```
