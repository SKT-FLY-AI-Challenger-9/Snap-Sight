# 편차 계산 인터페이스 제안 (② → ③)

> 상태: 제안 초안 — ② 담당자 최종 합의 필요
> **런타임 정본: 온디바이스 Kotlin — `frontend/.../cv/SpecDeviationCalculator.kt` (#29).**
> `backend/judgment/deviation.py` 는 이 계약의 레퍼런스 구현·테스트 벡터로 유지된다
> (실시간 판정은 온디바이스에서 수행 — 백엔드는 관여하지 않음).
> 관련: docs/detection-module.md, docs/detection-api-design.md (⑤×②가 먼저 제안한 필드에 맞춤)

## 배경 / 범위

- 이 문서가 다루는 것: `DetectionSignal` 입력 계약, `DeviationResult` 출력 계약, 프레이밍별 목표값
- 다루지 않는 것: "왼쪽으로 이동" 등 해석된 피드백(후속 이슈), Android 햅틱/사운드 매핑,
  서버 API 엔드포인트(`docs/detection-api-design.md`의 `POST /api/judge/aim`은 별도 논의)
- `subjectType`(person/object/landscape)은 이 함수와 무관하다 — `ai/target_spec_schema.md`에
  framing이 selector의 객체 선택과 독립적이라고 명시돼 있으며, 이 함수는 bbox의 종류를 모른 채
  `center_x`/`area_ratio`만 본다.

## DetectionSignal 입력 계약 (② 대상 제안)

| 필드 | 타입 | 범위 | 의미 |
|---|---|---|---|
| center_x | float | 0.0–1.0 | bbox 중심 x, 정규화 |
| area_ratio | float | 0.0–1.0 | bbox 면적 / 프레임 면적 |

- `docs/detection-module.md`가 제안한 `Detection.centerX`/`areaRatio`와 이름·의미를 맞췄다.
- 피사체 미검출 시, 그리고 `subjectType=landscape`(`scene_only`)처럼 애초에 겨냥할 대상이 없을 때
  모두 `detection=None`으로 전달한다 — 특수 sentinel 값을 쓰지 않는다.

## 프레이밍별 목표값 (1차 추정치 — 실측 검증 전, 튜닝 필요)

| framing | 목표 area_ratio |
|---|---|
| closeup | 0.30 |
| full_body | 0.12 |
| wide | 0.04 |

목표 위치는 모든 framing에 대해 `center_x = 0.5`로 고정.

## DeviationResult 출력 계약

| 필드 | 타입 | 의미 |
|---|---|---|
| subject_detected | bool | False면 두 편차 필드는 모두 None |
| x_deviation | float \| None | center_x − 0.5. 음수=타겟 왼쪽, 양수=오른쪽 |
| size_deviation | float \| None | area_ratio − 목표비율. 음수=너무 멂, 양수=너무 가까움 |

y축 편차는 없음 — ⑤·②의 제안 문서에 y축 관련 내용이 없어 이번 버전에서는 다루지 않는다.

부호를 보존하는 이유: 후속 이슈(햅틱·사운드 해석)가 이 함수를 재설계하지 않고 그대로
소비할 수 있게 하기 위함.

## 알려진 불일치 / 후속 논의

- `ai/on_device_cv/contracts.py`의 `BoundingBox`는 코너 포맷(x_min/y_min/x_max/y_max) 전체를
  다루는 저수준 타입 — 이 문서의 `DetectionSignal`(center_x/area_ratio)은 그중 편차 계산에 필요한
  두 값만 뽑은 상위 계약이다. 변환은 ②/⑤ 쪽 연동 코드에서 처리한다.
- `docs/detection-api-design.md`의 `POST /api/judge/aim`은 이 계산 결과를 서버 API로 감싸고
  해석된 `state`까지 반환하도록 제안하고 있다 — 이번 이슈 범위 밖이며, API 설계는 별도 이슈에서
  다룬다.
- landscape에서 편차 피드백 자체를 줄지 여부는 ⑥(UX) 정책 — 이 함수는 "미검출"로만 구분해
  넘겨줄 뿐 그 이후 판단은 하지 않는다.

## 변경 이력

- v0.1 (draft): 초안 작성 (Issue #25)
