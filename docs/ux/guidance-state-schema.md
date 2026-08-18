# GuidanceState 필드 정의 및 허용값 (초안)

> 문서 상태: 초안 — 프로토타입 전 잠정안, 임계값은 실측 전 추정치
> 관련 이슈: #5
> 선행 문서: docs/ux/capture-states.md, docs/interfaces/cv-to-ux-interface-draft.md, docs/ux/feedback-mapping.md

## 목적

이슈 #5의 "GuidanceState 필드 정의"와 "각 상태의 타입·허용값·기본 동작 명시" 항목을 채운다. `docs/deviation-interface.md`(v0.1, 이슈 #25/#29)가 정의한 출력값을 입력으로 받아, ⑥이 판정한 결과를 GuidanceState로 정의한다.

## 입력값 (`docs/deviation-interface.md` v0.1 기준)

| 필드 | 타입 | 상태 |
|---|---|---|
| `subject_detected` | Boolean | 확정 |
| `x_deviation` | Float? | 확정. center_x − 0.5, 음수=왼쪽/양수=오른쪽 |
| `size_deviation` | Float? | 확정. area_ratio − 목표비율, 음수=너무 멂/양수=너무 가까움 |

y축(`y_deviation`)은 계약에 없음 — v0.2 예정.

## GuidanceState (⑥ 내부 판정 결과)

실제 구현 언어는 Kotlin으로 확인됨 (`frontend/` 프로젝트 기준).

```kotlin
data class GuidanceState(
    val detected: Boolean,
    val horizontal: HorizontalAlignment?,  // detected == false면 null
    val distance: DistanceAlignment?       // detected == false면 null
)

enum class HorizontalAlignment { LEFT, RIGHT, CENTERED }
enum class DistanceAlignment { CLOSER, FARTHER, CENTERED }
```

- `detected = false` → 탐지 실패. `horizontal`/`distance` 모두 null
- `detected = true` && `horizontal = CENTERED` && `distance = CENTERED` → READY
- `horizontal = LEFT`/`RIGHT` → 방향 안내 필요
- `distance = CLOSER`/`FARTHER` → 거리 안내 필요

## 임계값(허용 오차) — [1차 실측 캘리브레이션 완료, 이슈 #42]

- `|x_deviation| <= 0.20` → CENTERED, 그 외 부호에 따라 LEFT/RIGHT (2026-08-19 실사용 피드백 "기준이 너무 빡셈"으로 0.15 → 0.20 완화)
- (additive, 2026-08-19) `vertical`: `y_deviation` 이 있을 때만 — `|y_deviation| <= 0.25` [추정] → CENTERED, 음수(피사체가 위) → UP, 양수 → DOWN. `isReady` 판정에는 포함하지 않는다
- READY 유지 히스테리시스(안내 정책 `GuidancePolicy`): 한 번 READY 에 들어오면 각 편차가 임계값 × 1.5 를 넘기 전까지 READY 로 유지 (손떨림 튐 방지)
- `|size_deviation| <= 0.10` → CENTERED, `size_deviation < -0.10` → CLOSER, `size_deviation > 0.10` → FARTHER

**근거 (갤럭시 S24, 7세션 실측 — 이슈 #42):** 사용자가 정상 조준 중일 때 편차 분포가
|x| 중앙값 0.123 / p90 0.255, |size| 중앙값 0.086 / p90 0.115 로 관측됐다. 기존 추정치(0.1/0.05)는
중앙값보다 작아 READY 도달률이 3%(33판정 중 1회)에 그쳤고, "안내 기준을 알 수 없다"는 사용성 문제로
직결됐다. 중앙값 + 손떨림 여유 기준으로 상향. 도달률 재측정과 사용자 테스트 기반 미세 조정은 후속.

## 다루지 않는 것

- y축 편차 반영 시의 판정 로직 (계약 v0.2 예정)
- 임계값의 최종 튜닝 (프로토타입 단계, 7단계 몫)

`subjectType=landscape` 정책은 확정됨 — `docs/ux/feedback-mapping.md` 참고 (편차 피드백 미제공).
