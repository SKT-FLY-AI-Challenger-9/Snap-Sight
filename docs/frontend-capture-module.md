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
│   ├── RingFrameBuffer.kt       PRE 333ms/POST 200ms bounded 링 버퍼 (보관 16장·후보 6장)
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
│   ├── FrameUploader.kt         POST /api/capture/frames → 서버 capture_revision 수신
│   ├── MetadataClient.kt        통합 understanding 폴링 (brief·detail·labels·revision·frame ID)
│   ├── FinalFrameClient.kt      exact revision canonical JPEG 다운로드·헤더 검증
│   ├── CaptureResultClient.kt   구버전 결과 폴링 호환용 (현재 MainActivity 흐름에서는 미사용)
│   ├── PhotoDescriptionClient.kt 구버전 설명 폴링 호환용 (현재 MainActivity 흐름에서는 미사용)
│   └── UtteranceClient.kt       발화 텍스트 → 백엔드 NLU 파싱 (TargetSpec)
├── stt/SpeechToTextRecognizer.kt 안드로이드 SpeechRecognizer 래퍼 (발화 인식)
├── tts/TtsPlayer.kt             로컬 TTS (백엔드 TTS 실패 시 폴백 채널)
└── ux/                          ⑥ 접근성 UI·햅틱·사운드 (⑥ 소유)
```

## 세션 흐름 (현재 구현)

볼륨 버튼 짧게 = 상태별 동작, 길게(≈1초) = 세션 취소.

```
IDLE ──볼륨──▶ LISTENING(발화 인식) ──볼륨──▶ PARSING(백엔드 NLU → TargetSpec)
──▶ AIMING(편차 피드백·자동 줌·PRE 버퍼) ──볼륨(셔터)──▶ CAPTURING(대표 컷 저장·POST 버퍼)
──▶ 업로드(대표 컷+후보 최대 6장+점수/회전) ──▶ SAVED(capture_revision)
──▶ MetadataClient 통합 폴링 ──▶ 필요 시 exact revision 최종 JPEG 저장·표시 ──▶ 음성 안내 ──▶ IDLE
```

1. PARSING은 인식 콜백 무응답 대비 8초 타임아웃 — 초과 시 스펙 없이 AIMING으로 진행
2. 업로드 실패는 촬영 성공과 분리 (사진은 MediaStore에 이미 저장)
3. 통합 결과 폴링: `MetadataClient` 하나가 `/metadata`의 `pending`/`done`/`failed`를 처리하고,
   업로드 응답에서 받은 `capture_revision`과 응답의 revision·`final_frame_id`를 검증한다. 제한은 120초다.
4. `final_frame_id == representative`면 원본을 유지한다. 후보가 canonical이면 `FinalFrameClient`가
   `?capture_revision=...`으로 JPEG를 받고 revision·frame ID 응답 헤더까지 일치한 뒤 MediaStore에 별도 저장·표시한다.
5. 후보 JPEG 픽셀에는 회전을 적용하지 않고 `candidate_scores.rotation_degrees`(0/90/180/270)로 전송한다.
   백엔드가 비교·설명 전에 정방향 JPEG로 정규화한다.

## 분석 부하·후보 버퍼

1. CameraX 분석은 상태별로 `OFF`/`WARM`/`ACTIVE`를 사용한다.

   - `OFF`: 홈·결과·설정 — ImageAnalysis use case 해제
   - `WARM`: LISTENING·PARSING — use case만 유지하고 analyzer 분리
   - `ACTIVE`: AIMING·CAPTURING·등록 — CV와 링 버퍼에 프레임 전달

2. `ACTIVE`에서도 detector를 매 프레임 호출하지 않는다. `SEARCHING`/`LOCKED`/`LOST`와 열 상태로
   wall-clock cadence를 조절하고, detector keyframe 사이에는 tracker `predictOnly` 결과를 propagation한다.
3. `RingFrameBuffer`는 기본 `OFF`다. AIMING에서 PRE_CAPTURE를 333ms 간격으로, 셔터 뒤
   POST_CAPTURE를 200ms 간격으로 샘플링하고 완료 뒤 다시 `OFF`가 된다.
4. JPEG 인코딩은 동시에 1개만 허용하고 내부 보관은 최대 16장이다. 셔터 전후 각 1초 범위에서
   최대 6장을 균등 추출해 업로드하므로 카메라 프레임을 상시 인코딩·전송하지 않는다.

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
1. 링 버퍼는 ⑤(Android 로컬) 보유 확정 — 촬영 시 `FrameUploader`가 필요한 프레임만 멀티파트 전송
2. 업로드 응답의 서버 `capture_revision`이 이후 폴링·최종 프레임 다운로드의 정본이다.
3. 현재 앱은 `/metadata` 한 곳에서 brief/detail/labels/`capture_revision`/`final_frame_id`를 폴링한다.
   `pending`은 재시도하고 `done`·`failed`·404·revision 불일치는 종료한다.
4. 후보 canonical JPEG는 `/api/capture/{id}/final-frame?capture_revision=...`에서 받고 응답 헤더도 검증한다.
5. 카메라 제어 API: `focusAt(x, y)`, `setExposure(-1f..1f)`, `setZoomRatio(ratio)`

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

1. debug 백엔드 주소: `-PBACKEND_BASE_URL=http://127.0.0.1:8000` (기본 `http://10.0.2.2:8000`)
2. release 백엔드 주소: `-PSNAPSIGHT_RELEASE_BACKEND_BASE_URL=https://api.example.com` (HTTPS 필수)
3. 서버 토큰을 켰다면 `-PSNAPSIGHT_API_TOKEN=...`도 함께 주입한다
4. 실기기 + WSL2 백엔드는 `adb reverse tcp:8000 tcp:8000` USB 터널 사용 — `docs/backend-local-setup.md` 참고
5. 프레임 스트림 확인: Logcat 필터 `tag:SnapSightFrames`; detector는 SEARCHING/LOCKED/LOST와 열 상태에 따라 주기가 바뀐다
6. 얼굴 크롭 디버그 덤프는 기본 비활성화다. 캘리브레이션 시에만 debug 빌드에 `-PENABLE_FACE_DEBUG_DUMPS=true`를 명시한다
