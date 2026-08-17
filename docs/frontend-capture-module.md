# ⑤ 카메라/센서 통합 모듈 가이드

담당: ⑤ 준서 · 위치: `frontend/app/src/main/java/com/example/snap_sight/`
팀 디렉토리 설계(camera / cv / network / stt / tts / ux)를 따른다.

## 구조 (⑤ 소유 파일 기준)

```
com.example.snap_sight
├── MainActivity.kt              앱 진입점 · 파이프라인 조립 (세션↔CV↔업로드↔안내 배선)
├── camera/                      ⑤ CameraX·트리거·센서
│   ├── CameraController.kt      파이프라인 (미리보기·분석·촬영·AF/AE·줌 setZoomRatio)
│   ├── CaptureSessionManager.kt 세션 상태 머신 (아래 "세션 흐름" 참고)
│   ├── AutoZoomController.kt    타겟 점유율 기반 자동 줌인/줌아웃 (아래 "자동 줌" 참고)
│   ├── FrameScorer.kt           후보 프레임 온디바이스 블러 점수 (라플라시안 분산)
│   ├── RingFrameBuffer.kt       촬영 전후 1초 후보 프레임 링 버퍼 (최대 6장)
│   ├── FrameAnalysisAdapter.kt  프레임 분배 (CV 프로세서 + 링 버퍼)
│   ├── YuvToJpeg.kt             YUV_420_888 → JPEG 변환 (stride 대응)
│   ├── TiltSensorMonitor.kt     IMU 가속도계 기울기 (AIMING 중만 동작)
│   ├── CaptureEventListener.kt  촬영 결과 수신 계약
│   └── audio/WavAudioRecorder.kt 마이크 녹음 → 16kHz mono WAV
├── cv/                          ② CV 모듈 + ⑤ 통합 지점
│   ├── SnapSightFrameProcessor.kt  ②의 파이프라인 진입점 (⑤가 create로 조립)
│   ├── SpecDeviationCalculator.kt  ⑤ 편차 계산기 — ②의 DeviationCalculator 계약 구현
│   ├── LabelMatchTargetSelector.kt ⑤ 임시 타겟 선택기 (② target_selection 포팅 전까지)
│   └── (Detector/Tracker/Contracts 등은 ② 소유)
├── network/                     ⑤ 백엔드 API 클라이언트
│   ├── FrameUploader.kt         POST /api/capture/frames (raw_text·블러 점수 포함)
│   ├── CaptureResultClient.kt   GET /api/capture/{id}/result 폴링 (MLLM 비교 결과)
│   └── UtteranceClient.kt       발화 텍스트 → 백엔드 NLU 파싱 (TargetSpec)
├── stt/SpeechToTextRecognizer.kt 안드로이드 SpeechRecognizer 래퍼 (발화 인식)
├── tts/TtsPlayer.kt             로컬 TTS (백엔드 TTS 실패 시 폴백 채널)
└── ux/                          ⑥ 접근성 UI·햅틱·사운드 (⑥ 소유)
```

## 세션 흐름 (실기기 E2E 검증됨 · Galaxy S24, 2026-08-14)

볼륨 버튼 짧게 = 상태별 동작, 길게(≈1초) = 세션 취소.

```
IDLE ──볼륨──▶ LISTENING(발화 인식) ──볼륨──▶ PARSING(백엔드 NLU → TargetSpec)
──▶ AIMING(편차 피드백·자동 줌·기울기 센서 ON) ──볼륨(셔터)──▶ CAPTURING
──▶ 업로드(대표 컷+후보 6장+raw_text+블러 점수) ──▶ SAVED ──▶ 결과 폴링 ──▶ 음성 안내 ──▶ IDLE
```

1. PARSING은 인식 콜백 무응답 대비 8초 타임아웃 — 초과 시 스펙 없이 AIMING으로 진행
2. 업로드 실패는 촬영 성공과 분리 (사진은 MediaStore에 이미 저장)
3. 결과 폴링: `CaptureResultClient` — pending이면 `retry_after_seconds`(기본 2초) 간격 재시도,
   총 45초 제한, `done`이면 개선 여부를 음성 안내 ("더 나은 순간의 사진으로 교체했어요" 등)
4. 알려진 한계: 후보 JPEG는 회전 미적용 원본 (회전값은 파일명 `_r90` 형태) — 서버 전처리에서 보정

## 편차·판정 (④ 연속 피드백)

계약은 `docs/deviation-interface.md`가 기준. 요약:

1. `SpecDeviationCalculator`가 타겟 bbox → x 편차(중심-0.5)·크기 편차(점유율-프레이밍 목표) 계산
2. 프레이밍 목표 점유율: closeup 0.30 / full_body 0.12 / wide 0.04
3. READY(촬영 적기) 판정: |x 편차| ≤ 0.15 그리고 |크기 편차| ≤ 0.10
   — S24 실측(중앙값 x 0.123 / size 0.086) 기반 캘리브레이션, 근거는 `docs/ux/guidance-state-schema.md`
4. 편차 계산은 전부 온디바이스 — 백엔드는 피드백 루프에 들어오지 않음

## 자동 줌 (AutoZoomController)

발화로 지정한 타겟의 화면 점유율에 따라 줌을 자동 조정한다.

1. 점유율 20% 미만 → 줌인, 60% 초과 → 줌아웃, 목표는 40%
2. 필요 배율 = 현재 배율 × √(0.40 / 현재 점유율) (면적은 배율 제곱에 비례)
3. 연속 트리거 방지 2초 쿨다운, AIMING 진입 시 1배로 리셋

## 후보 점수 (FrameScorer)

업로드 전 각 후보 프레임의 블러 점수를 온디바이스에서 계산해 함께 전송한다.
160px 다운스케일 → BT.601 휘도 → 3×3 라플라시안 분산 → 점수 = 1 − clamp(분산/300).
점수가 낮을수록 선명. 서버 MLLM이 후보 비교 시 참고 신호로 사용.

## 팀별 연동 방법

### ② 온디바이스 CV (종윤)
`SnapSightFrameProcessor.create(...)`에 ⑤가 `deviationCalculator = SpecDeviationCalculator()`,
`selector = LabelMatchTargetSelector()`를 주입해 조립한다.
1. 호출 스레드: 카메라 분석 전용 단일 스레드 (메인 아님)
2. 백프레셔: `KEEP_ONLY_LATEST` — 추론이 느리면 중간 프레임 자동 스킵
3. `.tflite` 모델은 `frontend/app/src/main/assets/` 배치 (커밋 금지 — .gitignore가 `*.tflite` 제외)
4. `LabelMatchTargetSelector`는 ②의 target_selection 포팅 전 임시 — 스펙 라벨 일치 필터만 수행

### ③·④ 백엔드 (봄연)
1. 링 버퍼는 ⑤(Android 로컬) 보유 확정 — 촬영 시 `FrameUploader`가 멀티파트 전송
2. 전송 필드: `session_id`, 대표 컷, 후보 프레임들, `raw_text`(원 발화), `candidate_scores`(블러)
3. 결과 계약: `pending`(+retry_after_seconds) / `done`(+improved·reason) / 404 — `docs/backend-local-setup.md` 참고
4. 카메라 제어 API: `focusAt(x, y)`, `setExposure(-1f..1f)`, `setZoomRatio(ratio)`

### ① STT/NLU (숩젼)
1. 발화 인식은 현재 안드로이드 `SpeechRecognizer` 사용 (`stt/SpeechToTextRecognizer`)
2. 인식 텍스트를 `UtteranceClient`로 백엔드 NLU에 보내 `TargetSpec` 수신
3. `WavAudioRecorder`(16kHz mono WAV)는 외부 STT API 전환 대비용으로 유지

### ⑥ UX/피드백 (숩젼)
1. `CaptureEventListener.onShutter()` → 셔터 사운드/진동 타이밍
2. 편차 판정 결과는 `GuidanceStateMapper`(⑥)로 전달 — 임계값은 ⑤ 캘리브레이션과 동기화(0.15/0.10)
3. 백엔드 TTS 실패 시 `GuidanceFeedback.announce()`로 로컬 TTS 폴백

## 기술 결정 사항

| 항목 | 선택 | 이유 |
|---|---|---|
| 카메라 API | CameraX | 갤럭시 기종 파편화 흡수, 수명주기 자동 관리 |
| 촬영 모드 | `MINIMIZE_LATENCY` | 순간 포착 목적 — 셔터 지연 최소화 우선 |
| 프레임 백프레셔 | `KEEP_ONLY_LATEST` | 실시간 조준 보조에는 최신 프레임만 의미 있음 |
| 편차 계산 위치 | 온디바이스 | 연속 피드백 루프에 네트워크 왕복 불가 |
| 초점 | 연속 AF + `focusAt()` 지시형 | 시각장애인은 탭 투 포커스 불가 → 좌표 지시 |
| SDK | compileSdk 37 / target 36 / min 26 | min 26은 README 요구사항 (API 26+) |

## 빌드·실행

```bash
cd frontend
./gradlew assembleDebug          # Windows: .\gradlew.bat assembleDebug
```

1. 백엔드 주소는 BuildConfig 주입: `-PBACKEND_BASE_URL=http://127.0.0.1:8000` (기본 `http://10.0.2.2:8000`)
2. 실기기 + WSL2 백엔드는 `adb reverse tcp:8000 tcp:8000` USB 터널 사용 — `docs/backend-local-setup.md` 참고
3. 프레임 스트림 확인: Logcat 필터 `tag:SnapSightFrames` (S25 Ultra 실측 약 6.3fps, p50 154ms)
