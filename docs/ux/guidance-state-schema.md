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

## 임계값(허용 오차) — [추정, 프로토타입에서 튜닝 필요]

- `|x_deviation| <= 0.1` → CENTERED, 그 외 부호에 따라 LEFT/RIGHT
- `|size_deviation| <= 0.05` → CENTERED, `size_deviation < -0.05` → CLOSER, `size_deviation > 0.05` → FARTHER

**두 숫자(0.1, 0.05) 모두 선행연구나 실측 데이터 근거가 없는 순수 추정치다.** 실제 기기에서 값 분포를 관찰한 뒤 조정이 필요하다.

## 다루지 않는 것

- `subjectType=landscape`(피사체 없음)일 때의 판정 정책 — 별도 결정 필요
- y축 편차 반영 시의 판정 로직 (계약 v0.2 예정)
- 임계값의 최종 튜닝 (프로토타입 단계, 7단계 몫)
