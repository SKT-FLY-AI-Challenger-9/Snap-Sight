# Snap-Sight Accessibility Guidelines

> 이 문서는 Snap-Sight의 UI 및 피드백 구현 시 우선적으로 참고해야 하는 접근성 UX 기준이다.
> 외부 표준(WCAG)과 연구 근거 기반 원칙을 함께 포함하며,
> 구현 시 본 문서와 충돌하는 UI/피드백 패턴은 별도 검토한다.

## 1. Accessibility Target

Snap-Sight는 WCAG 2.2 Level A 및 AA 성공 기준을
Android 네이티브 앱에 적용 가능한 범위에서 준용한다.

모바일 앱 적용 해석은 W3C의 WCAG2Mobile 및 WCAG2ICT를 참고하며,
시각장애인의 카메라 촬영이라는 서비스 특성은 별도의 사용자 연구 및
시각장애인 사진 촬영 관련 선행연구를 통해 보완한다.

※ WCAG2Mobile은 현재 informative guidance이며,
Snap-Sight가 공식적인 WCAG 인증 또는 적합성 평가를 받았음을 의미하지 않는다.

## 2. Platform & Screen-reader Accessibility

WCAG는 UI를 사용자가 인지·조작·이해할 수 있는지를 다룬다. Snap-Sight의 화면(S1/S2/S5 등)과
공통 컨트롤(버튼, 라벨, 상태 안내)에는 아래 성공 기준을 적용 가능한 범위에서 준용한다.

- **Label / Role / State** — WCAG 1.1.1 Non-text Content (A)
  아이콘이나 비텍스트 컨트롤에는 동일 목적을 전달하는 접근 가능한 이름이 필요하다.
  예: 카메라 아이콘만 있는 컨트롤이면 TalkBack에서 "촬영"으로 인식돼야 한다.
  TalkBack에서 역할/상태가 명확히 전달되도록 구현한다.
- **탐색 순서** — 탐색 순서를 단순하고 예측 가능하게 구성한다(화면 배치 순서 = 낭독 순서).
- **색상 외 정보 제공** — WCAG 1.4.1 Use of Color (A)
  READY=초록, LOST=빨강처럼 색상만으로 상태를 구분하지 않는다. 텍스트·음성·햅틱 등
  다른 단서를 함께 제공한다.
- **화면 방향** — WCAG 1.3.4 Orientation (AA)
  특정 화면 방향만 강제하지 않는다. 다만 촬영 기능상 특정 방향이 본질적으로 필요한
  경우는 예외로 검토한다.
- **대체 입력 방식** — WCAG 2.5.7 Dragging Movements (AA), 입력 수단 다양성
  드래그 동작만으로 조작하게 하지 않고 대체 조작을 제공한다. 볼륨 버튼을 주요 조작
  수단으로 두더라도, 화면의 접근 가능한 터치 컨트롤을 완전히 없애지 않는다.
- **터치 타깃** — WCAG 2.5.8 Target Size Minimum (AA)
  주요 터치 영역을 최소 크기 이상으로 확보한다(Snap-Sight 기준 48dp).
- **오류 및 다음 행동 안내** — WCAG 3.3.2 Labels or Instructions (A)
  사용자 입력이 필요한 경우 다음 행동을 구체적으로 안내한다.
  - X: "버튼을 눌러주세요"
  - O: "볼륨 버튼을 눌러 촬영하세요"
- 장식 요소는 스크린리더 탐색에서 제외한다.

## 3. Camera Interaction Accessibility

WCAG는 실시간 카메라 촬영 경험(방향 안내, 피사체 프레이밍) 자체를 규정하지 않는다.
이 영역은 시각장애인 사진 촬영 관련 선행연구와 자체 사용자 검증을 근거로 삼는다.

- 촬영 의도를 먼저 받는다(예: "강아지를 찍어줘" → 촬영 의도 인식 → 탐색).
- 사용자의 촬영 주체성을 유지한다 — 자동 촬영보다 사용자가 직접 셔터를 조작하는 것을 기본으로 한다.
- 실시간 방향 안내(LEFT/RIGHT/CLOSER/FARTHER)는 사운드·햅틱 중심으로 전달한다.
- 주요 상태 변화(READY/LOST)만 짧은 TTS로 1회 안내한다.
- 저시력 사용자를 위한 시각 정보를 병행한다.
- 주변 청각 정보(대화, 환경음)를 방해하지 않도록 피드백 빈도·길이를 최소화한다.

### 상태별 기본 피드백

| 상태 | 기본 피드백 |
|---|---|
| LEFT / RIGHT | 비언어 사운드 + 햅틱 |
| CLOSER / FARTHER | 비언어 사운드 + 햅틱 |
| READY | 짧은 TTS |
| LOST | 짧은 TTS |
| 촬영 완료 | 짧은 완료 피드백 |

## 4. Practice References

웹/스크린리더 접근성 실무 자료. ARIA(`role`, `aria-*`) 등 웹 표준 API는 Android에 그대로
이식되지 않으므로, Snap-Sight Android 환경에는 모바일 구현 시 참고 기준으로만 활용한다.

- FEConf 2021, 「모두를 위한 모바일웹, 접근성을 준수해서 스크린리더 UX 개선하기」
- 「시각장애인을 위한 UI/UX 디자인」 (Brunch)

## 5. Research Evidence

- 기존에 정리한 시각장애인 사진 촬영 및 햅틱/음향 안내 관련 논문들

## 6. 개발 시 체크

- UI 추가/수정 시 본 문서 확인
- TalkBack 탐색 및 라벨 고려
- 앱 TTS와 TalkBack 충돌 가능성 확인
- 시각 정보만으로 핵심 상태를 전달하지 않기
- 기존 피드백 규칙을 임의로 변경하지 않기
