# Android assets — ② 온디바이스 CV 모델 자산

`com.example.snap_sight.cv.TfLiteYoloDetector` 가 여기서 두 파일을 읽는다.

| 파일 | 설명 |
| --- | --- |
| `objects365_yolo26_v1.tflite` | Objects365 365-class YOLO detector (TFLite) |
| `objects365_yolo26_v1_labels.txt` | 줄 번호 = class ID 인 라벨 목록 (365줄) |

두 파일 모두 저장소 루트에서 생성한다.

```powershell
python -m pip install "ultralytics" tensorflow
python -m ai.tools.export_tflite
```

스크립트는 다음을 수행한다.

1. `yolo26n-objv1-150.pt` 의 `model.names` 가 `ai/taxonomy/objects365_yolo26_v1.json` 과
   class ID 순서까지 일치하는지 검증 (어긋나면 export 하지 않고 실패)
2. TFLite export
3. 이 디렉터리로 모델 복사 + 라벨 파일 생성
4. 출력 텐서 shape/dtype 출력 — detector 가 자동 판별하는 layout 을 눈으로 확인하는 용도

자산이 없어도 앱은 죽지 않는다. `SnapSightFrameProcessor` 가 로드 실패를 잡아
"검출 0개" 로 계속 동작하므로 카메라·세션·업로드 경로는 그대로 검증할 수 있다.
로그 태그 `SnapSightCV` 에 실패 사유가 남는다.

## 얼굴 임베더 (기능 2 — `com.example.snap_sight.face.TfLiteFaceEmbedder`)

| 파일 | 설명 |
| --- | --- |
| `face_embedder.tflite` | 얼굴 임베딩 모델 — 112×112 RGB float 입력, float 임베딩 벡터 출력 |

MobileFaceNet 계열 공개 모델을 배치한다 (입력 정규화 `(pixel − 127.5) / 128` 기준).
예: [MobileFaceNet TFLite 변환본](https://github.com/sirius-ai/MobileFaceNet_TF) 을 tflite 로
export 하거나, InsightFace 의 MobileFaceNet onnx 를 tflite 로 변환한다.
없으면 인물 인식·얼굴 등록 기능만 조용히 꺼진다 (`FaceEmbedder.isAvailable=false`,
로그 태그 `TfLiteFaceEmbedder`). 나머지 앱 동작에는 영향 없다.

## 검색용 고정 라벨 사전 (기능 3)

| 파일 | 설명 |
| --- | --- |
| `photo_labels.json` | 사진 검색용 고정 라벨 사전 — `ai/taxonomy/photo_labels.json` 의 복사본 |

**직접 수정하지 말 것** — 정본은 `ai/taxonomy/photo_labels.json` 이며, 수정 후 이 파일로
복사한다. 두 파일이 다르면 `tests/test_photo_labels.py` 가 실패한다.

> `.tflite` 는 저장소 전역 `.gitignore` 대상이지만 이 디렉터리만 예외로 커밋을 허용한다.
