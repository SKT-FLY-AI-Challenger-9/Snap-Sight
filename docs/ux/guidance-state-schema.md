# GuidanceState 필드 정의 및 허용값 (초안)

> 문서 상태: 초안 — 프로토타입 전 잠정안, 임계값은 실측 전 추정치
> 관련 이슈: #5
> 선행 문서: docs/ux/capture-states.md, docs/interfaces/cv-to-ux-interface-draft.md, docs/ux/feedback-mapping.md

## 목적

이슈 #5의 "GuidanceState 필드 정의"와 "각 상태의 타입·허용값·기본 동작 명시" 항목을 채운다. ②가 제공하는 원시 값(Document D 확정 사항)을 입력으로 받아, ⑥이 판정(Document D 질문 1 — 판정은 ⑥ 담당으로 확인됨)한 결과를 GuidanceState로 정의한다.

## 입력값 (②로부터, Document D 기준)

| 필드 | 타입 | 상태 |
|---|---|---|
| dx | Float | 확정, 지금 제공됨. 정규화된 값으로 추정(이슈 #2의 "정규화된 bbox" 근거) — 정확한 값 범위는 ② 미확인 |
| detected | Boolean | 확정 — LOST/AMBIGUOUS를 하나로 합친 값 |
| dy | Float? | 계산은 되나 현재 미제공/미사용. 반영 시점 미정 |
| sizeRatio | Float? | 미구현 |

## GuidanceState (⑥ 내부 판정 결과)

아래는 개념을 코드 형태로 표현한 예시다. **실제 구현 언어(Kotlin 여부 포함)는 아직 확정되지 않았다** — 관례상 Kotlin 문법으로 적었을 뿐이다.

```kotlin
data class GuidanceState(
    val detected: Boolean,
    val horizontal: HorizontalAlignment?  // detected == false면 null
)

enum class HorizontalAlignment { LEFT, RIGHT, CENTERED }
```

- `detected = false` → 탐지 실패(LOST/AMBIGUOUS 합침). `horizontal`은 의미 없음(null)
- `detected = true` && `horizontal = CENTERED` → READY에 해당 (Document E 기준)
- `detected = true` && `horizontal = LEFT` 또는 `RIGHT` → 방향 안내 필요

dy, sizeRatio는 현재 판정에 관여하지 않으므로 GuidanceState에 포함하지 않았다. 반영되면 이 스키마부터 다시 열어야 한다.

## 임계값(허용 오차) — [추정, 프로토타입에서 튜닝 필요]

dx가 정규화된 값(대략 -1.0~1.0 범위로 추정)이라는 가정하에 잠정 값을 둔다.

- `|dx| <= 0.1` → CENTERED
- `dx < -0.1` → LEFT
- `dx > 0.1` → RIGHT

**0.1이라는 숫자는 선행연구나 실측 데이터 근거가 없는 순수 추정치다.** 실제 안드로이드 기기에서 dx 값의 실제 분포를 관찰한 뒤 조정이 필요하다.

## 다루지 않는 것

- dy, sizeRatio 필드가 실제로 반영될 때의 판정 로직
- 실제 구현 언어 확정
- 임계값의 최종 튜닝 (프로토타입 단계, 7단계 몫)
