# 탐지 결과 → 판정 API 연동 기반 설계 (⑤ → ③)

온디바이스 탐지(`cv/YoloFrameProcessor`)가 산출한 `Detection` 을 백엔드 판정 로직(③)에
전달하고, 조준 안내(⑥ GuidanceState)의 근거가 될 판정을 받아오기 위한 **설계 초안**이다.
구현 전 ③ 담당자와 합의가 필요하다.

## 설계 원칙

1. **판정에 필요한 최소 데이터만 전송** — 프레임 이미지가 아니라 탐지 결과(수십 바이트)만.
   기존 `POST /api/capture/frames` 는 세션 종료 후 대표 컷 업로드용이고, 이 API 는
   조준 중(AIMING) 실시간 왕복용으로 역할이 다르다.
2. **왕복 지연 예산 ≈ 200ms** — 조준 피드백은 실시간성이 생명. 초과 시 ③ 로직의
   온디바이스 이관(⑤ 내 구현)을 검토한다. 그래서 요청/응답을 최대한 얇게 유지한다.
3. **좌표는 항상 0..1 정규화 + 정방향 기준** — 해상도·회전 협상을 없앤다.

## 제안 엔드포인트

```
POST /api/judge/aim
Content-Type: application/json
```

### 요청

```json
{
  "sessionId": "s_20260814_153012",
  "schemaVersion": "0.1",
  "frame": { "capturedAtMs": 1765700000123, "tiltRollDeg": -2.5, "tiltPitchDeg": 1.0 },
  "targetSpec": { "subjectType": "object", "objectLabel": "cup", "subjectCount": 1, "framing": "closeup" },
  "detections": [
    { "label": "cup", "score": 0.87, "box": [0.41, 0.30, 0.58, 0.55] }
  ]
}
```

- `targetSpec` 은 ① 파싱 결과 중 판정에 필요한 필드만 (전체 스펙은 세션 시작 시 1회 전달 가능).
- `detections[].box` = `[left, top, right, bottom]`, 정방향 프레임 0..1 정규화.
- `tilt*` 는 `TiltSensorMonitor` 값 — 수평 안내 판정용.
- 상위 5개 이내로 잘라 보낸다 (score 내림차순).

### 응답 (→ ⑥ GuidanceState 의 원천, Issue #5 와 필드 합의)

```json
{
  "sessionId": "s_20260814_153012",
  "state": "MOVE_LEFT",
  "deviationX": 0.18,
  "areaRatio": 0.04,
  "targetFound": true,
  "readyToCapture": false
}
```

- `state`: `MOVE_LEFT` | `MOVE_RIGHT` | `MOVE_CLOSER` | `MOVE_BACK` | `READY` | `TARGET_LOST`
  — #5 의 GuidanceState 상태값과 1:1 로 맞춘다.
- `deviationX`: 타겟 중심의 화면 중심 대비 편차 (-0.5..0.5, 음수 = 타겟이 왼쪽).
- `readyToCapture = true` 이면 ⑤가 자동 셔터를 트리거할 수 있다.

## 전송 정책

- AIMING 상태에서만 전송, 주기는 최대 5회/초로 스로틀 (탐지 fps 가 더 높아도 제한).
- `TARGET_LOST` 판정 근거를 위해 탐지가 비어도 빈 배열로 전송한다.
- 실패/타임아웃 시 조용히 스킵 — 조준 피드백은 최신 값만 의미 있으므로 재시도하지 않는다.

## 미결정 사항 (③·⑥ 합의 필요)

- [ ] 판정 로직 위치: 백엔드 vs 온디바이스 (지연 예산 측정 후 결정)
- [ ] `state` 값 목록과 #5 GuidanceState 필드 최종 정합
- [ ] 세션 시작 시 targetSpec 전체를 등록하는 별도 엔드포인트 필요 여부
- [ ] WebSocket 전환 여부 (HTTP 폴링 지연이 예산을 넘을 경우)
