# ⑤ 카메라/센서 통합 모듈 가이드

> 담당: ⑤ 준서 · 위치: `frontend/app/src/main/java/com/example/snap_sight/`
> 팀 디렉토리 설계(camera / cv / network / ux)를 따른다.

## 구조

```
com.example.snap_sight
├── MainActivity.kt              앱 진입점, 권한 처리, 볼륨 버튼 훅
├── camera/                      ⑤ CameraX·트리거·센서
│   ├── CameraController.kt      파이프라인 (미리보기·분석·촬영·AF/AE·렌즈전환)
│   ├── FrameAnalysisAdapter.kt  프레임 분배 (CV 프로세서 + 링 버퍼)
│   ├── CaptureEventListener.kt  촬영 결과 수신 계약
│   └── audio/WavAudioRecorder.kt 마이크 녹음 → 16kHz mono WAV (① STT 입력용)
├── cv/                          ⑤ TFLite 통합 지점 (② 모듈이 여기로 들어옴)
│   ├── FrameProcessor.kt        ② CV 모듈이 구현하는 프레임 수신 계약
│   └── LoggingFrameProcessor.kt 파이프라인 검증용 더미 (FPS 로그)
├── network/                     ⑤ 백엔드 API 클라이언트
├── ux/                          ⑥ 접근성 UI·햅틱·사운드
│   └── CaptureScreen.kt         임시 화면 (⑥이 정식 화면으로 교체)
└── ui/theme/                    Compose 테마
```

## 팀별 연동 방법

### ② 온디바이스 CV (종윤)
`cv.FrameProcessor` 를 구현한 클래스를 만들어 주세요.

```kotlin
class YoloFrameProcessor : FrameProcessor {
    override fun onAttached() { /* TFLite 모델 로딩 */ }
    override fun onFrame(image: ImageProxy, rotationDegrees: Int) {
        // YUV_420_888 프레임. 동기적으로 추론하거나 데이터 복사 후 리턴.
        // 리턴하면 ⑤가 image 를 close 하므로 참조를 보관하면 안 됨.
        // 결과(bbox)는 ③ 판정 로직으로 전달.
    }
    override fun onDetached() { /* 모델 해제 */ }
}
```

연결: `cameraController.setFrameProcessor(YoloFrameProcessor())`
- 호출 스레드: 카메라 분석 전용 단일 스레드 (메인 아님)
- 백프레셔: `KEEP_ONLY_LATEST` — 추론이 느리면 중간 프레임 자동 스킵
- `.tflite` 모델은 `frontend/app/src/main/assets/` 에 배치 (저장소에는 커밋하지 않음 — 루트 .gitignore 가 `*.tflite` 제외)

### ③ 판정 로직 (봄연)
- ⑤가 제공하는 카메라 제어 API: `focusAt(x, y)` (피사체 좌표로 초점 지시), `setExposure(-1f..1f)`

### ④ 저장·MLLM (봄연)
- 링 버퍼는 **⑤(Android 로컬)** 보유로 확정 (`backend/api/capture.py` 기준)
- 촬영 시 `network` 패키지의 업로더가 `POST /api/capture/frames` 로
  `session_id` + 대표 컷 + 후보 프레임들을 멀티파트 전송

### ① STT/NLU (숩젼)
`camera.audio.WavAudioRecorder` 가 16kHz / mono / 16bit PCM WAV 를 만들어 줍니다.
```kotlin
recorder.start(File(filesDir, "voice.wav"))
val wav: File? = recorder.stop()   // 이 파일을 STT API 로 업로드
```
- 오디오 소스: `VOICE_RECOGNITION` (음성인식 최적화 프로파일)

### ⑥ UX/피드백 (숩젼)
- `camera.CaptureEventListener.onShutter()` → 셔터 사운드/진동 재생 타이밍
- `onCaptureError()` → 실패 음성 안내 타이밍
- `ux.CaptureScreen` 은 임시 화면 → `docs/screen-design.md` 의 S3 화면으로 교체

## 기술 결정 사항

| 항목 | 선택 | 이유 |
|---|---|---|
| 카메라 API | CameraX (Camera2 아님) | 갤럭시 기종 파편화 흡수, 수명주기 자동 관리 |
| 촬영 모드 | `MINIMIZE_LATENCY` | 순간 포착 목적 — 셔터 지연 최소화 우선 |
| 프레임 백프레셔 | `KEEP_ONLY_LATEST` | 실시간 조준 보조에는 최신 프레임만 의미 있음 |
| 오디오 포맷 | 16kHz mono 16bit WAV | 구글/네이버/AWS STT 공통 표준 입력 |
| 초점 | 연속 AF 기본 + `focusAt()` 지시형 | 시각장애인은 탭 투 포커스 불가 → ③이 좌표 지시 |
| SDK | compileSdk 37 / target 36 / min 26 | min 26 은 README 요구사항 (API 26+) |

## 빌드·실행

```bash
cd frontend
./gradlew assembleDebug          # Windows: .\gradlew.bat assembleDebug
```
에뮬레이터 스모크 테스트: 미리보기 표시 → 볼륨 버튼 촬영 → `Pictures/SnapSight/` 저장 확인.
프레임 스트림 확인: Logcat 필터 `tag:SnapSightFrames` (약 26fps@640x480, Pixel 에뮬레이터 기준).
