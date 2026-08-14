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

> `.tflite` 는 저장소 전역 `.gitignore` 대상이지만 이 디렉터리만 예외로 커밋을 허용한다.
