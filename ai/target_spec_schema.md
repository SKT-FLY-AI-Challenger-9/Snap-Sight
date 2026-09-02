# 타겟 스펙(Target Spec) 인터페이스 계약

STT/NLU(①)가 사용자 발화를 파싱해 생성하고, 백엔드 판정 로직(③)으로 전달하는 데이터 구조입니다.

## 필드 정의

| 필드 | 타입 | 허용값 | 기본값 | 설명 |
| --- | --- | --- | --- | --- |
| schemaVersion | string | `0.1` \| `0.2` | "0.1" | 생략하면 기존 호환을 위해 0.1. objectLabel 사용 시 0.2를 명시한다 |
| sessionId | string | - | (필수, 기본값 없음) | 촬영 세션 식별자. 로그·디버깅·MLLM 검증 결과 추적용 |
| status | string | `ok` \| `needs_clarification` \| `failed` | "ok" | 파싱 결과 상태 |
| subjectType | string | `person` \| `object` \| `landscape` | "person" | 피사체 종류 |
| objectLabel | string \| null | 아래 Objects365 canonical label | null | v0.2에서 subjectType이 "object"일 때만 사용 |
| subjectCount | int \| null | 1 이상 정수 또는 null | null | 요청된 인원/개체 수 (본인 제외, 미지정 시 null) |
| framing | string | `closeup` \| `full_body` \| `wide` | "full_body" | 프레이밍 스타일 |
| rawText | string | - | (필수, 기본값 없음) | 원본 발화 텍스트 (디버깅/로그용, MLLM 컨텍스트로도 활용) |
| confidence | float | 0.0 ~ 1.0 | 0.0 | 파싱 신뢰도 |
| source | string | `clova` \| `elevenlabs` \| `ondevice` | - | 사용된 STT 엔진 |

## status 값 정의

- `ok`: 정상 파싱됨. 그대로 사용 가능.
- `needs_clarification`: confidence가 낮거나 필수 슬롯이 비어있어 재질문이 필요함.
- `failed`: STT 자체가 실패했거나 텍스트가 비어있음 (rawText가 빈 문자열일 수 있음).

## subjectType / framing 값 정의

- `subjectType`: `person`(인물) / `object`(사물) / `landscape`(풍경)
- `framing`: `closeup`(얼굴·클로즈업) / `full_body`(전신, 기본값) / `wide`(풍경·배경 위주)

wire 형식과 기본값은 `ai/target_spec.py`의 `TargetSpec`이 검증한다. STT/NLU parser는 이 저장소의
현재 CV 범위에 포함되지 않으며, 허용값을 변경할 때 이 문서와 validator를 함께 갱신한다.

## On-device CV 적용 규칙

① STT/NLU가 생성한 이 JSON은 `ai.target_spec.TargetSpec`으로 검증한 뒤
`ai.on_device_cv.target_selection.TargetSelector`에 전달한다. CV는 TargetSpec 때문에
detector 입력을 제한하지 않는다. Objects365가 지원하는 전체 객체를 먼저 탐지·추적한 뒤,
현재 프레임의 tracking 결과에서 의도에 맞는 후보를 선택한다. 따라서 세션 중 TargetSpec이
바뀌어도 이미 추적 중인 객체의 `track_id`는 유지된다.

- `status=ok`: `subjectType`에 따른 후보 선택을 수행한다.
- `status=needs_clarification|failed`: 임의의 객체를 선택하지 않고 `unresolved`로 처리한다.
- `subjectType=person`: Objects365 `Person` 후보만 반환한다.
- `subjectType=object`, v0.1 또는 `objectLabel=null`: `person`을 제외한 Objects365 객체 후보를
  모두 반환한다.
- `subjectType=object`, v0.2의 `objectLabel` 지정: 해당 Objects365 class의 후보만 반환한다.
- `subjectType=landscape`: Objects365는 scene 분류기가 아니므로 객체를 억지로 선택하지 않고
  `scene_only`로 처리한다.
- `status=ok`이고 `subjectType=person|object`일 때 `subjectCount=null`이면 개수 제한 없이
  일치하는 후보를 모두 반환한다.
- 같은 조건에서 `subjectCount=N`이면 검출 수를 `under|exact|over`로 판정한다. 후보가 N개보다 많아도 현재
  스키마에는 특정 후보를 구분할 identity 정보가 없으므로 임의로 N개를 잘라내지 않는다.
- `landscape` 또는 unresolved 상태에서는 count를 `not_applicable`로 처리한다.
- 현재 detector만으로 촬영자 본인과 다른 사람을 식별할 수 없으므로 `subjectCount`의
  "본인 제외" 의미는 identity 기능이 추가되기 전까지 CV가 보장하지 않는다.
- `framing`: 현재 selector의 객체 종류 선택에는 사용하지 않는다. 향후 구도·편차 판단 단계가
  `closeup|full_body|wide`를 해석한다.

## objectLabel 값 정의

`subjectType="object"`일 때 어떤 사물인지 식별하는 v0.2 필드다. 허용값의 단일 기준은
[`ai/taxonomy/objects365_yolo26_v1.json`](taxonomy/objects365_yolo26_v1.json)의 `labels` 중
`person`을 제외한 169개 값이다. 이 목록은 실제 배포 checkpoint
`yolo26n-kr170-v5.pt`의 170개 `model.names`와 class ID 순서까지 일치한다.

- 값은 모델의 canonical label을 그대로 사용한다. 영문 소문자와 내부 공백/슬래시를
  보존한다. 예: `cup`, `wine glass`, `cell phone`, `cabinet/shelf`.
- `wine_glass`, `cell_phone` 같은 COCO식 underscore 값은 허용하지 않는다.
- `bird`는 Objects365 단일 class가 아니다. `wild bird`, `pigeon`, `duck`, `parrot` 등
  정확한 class로 해석할 수 없다면 임의 매핑하지 않는다.
- NLU가 발화를 정확한 canonical label로 해석하지 못했다면 `objectLabel=null`과
  `status=needs_clarification`을 사용한다. CV는 `rawText`를 다시 파싱하지 않는다.
- 일반적인 "사물을 찍어줘"처럼 특정 종류를 요구하지 않았다면 `status=ok`과
  `objectLabel=null`을 사용하며, 이때 모든 지원 non-person 객체가 후보가 된다.
- `subjectType`이 `object`가 아니면 `objectLabel`은 반드시 `null`이어야 한다.
- v0.1 payload에는 `objectLabel` 키를 넣지 않는다. 신규 producer는 v0.2를 명시한다.
- v0.2에서 `objectLabel` 키를 생략하면 `null`로 정규화되며, 직렬화할 때는 명시적으로
  `"objectLabel": null`을 출력한다.

## 예시 JSON

```json
{
  "schemaVersion": "0.2",
  "sessionId": "sess_20260812_001",
  "status": "ok",
  "subjectType": "person",
  "objectLabel": null,
  "subjectCount": 2,
  "framing": "closeup",
  "rawText": "친구 두 명이랑 같이 나오게, 얼굴 크게 찍어줘",
  "confidence": 0.9,
  "source": "clova"
}
```

사물(object) 예시:
```json
{
  "schemaVersion": "0.2",
  "sessionId": "sess_20260813_002",
  "status": "ok",
  "subjectType": "object",
  "objectLabel": "cup",
  "subjectCount": 1,
  "framing": "closeup",
  "rawText": "저 컵 예쁘게 찍어줘",
  "confidence": 0.85,
  "source": "clova"
}
```

## 변경 이력
- 0.1: 초안 작성 — subjectType/subjectCount/framing/rawText/confidence
- 0.1(수정1): schemaVersion/sessionId/status/source 필드 추가
- 0.1(수정2): 각 필드 허용값·기본값 명시 (subjectType/framing enum 반영)
- 0.1(수정3): On-device CV의 post-tracking 선택 및 count 처리 규칙 명시
- 0.2: objectLabel 필드 추가 및 Objects365 365-class taxonomy로 허용값 확정
