# 타겟 스펙(Target Spec) 인터페이스 계약

STT/NLU(①)가 사용자 발화를 파싱해 생성하고, 백엔드 판정 로직(③)으로 전달하는 데이터 구조입니다.

## 필드 정의

| 필드 | 타입 | 허용값 | 기본값 | 설명 |
| --- | --- | --- | --- | --- |
| schemaVersion | string | - | "0.1" | 스키마 버전. 필드 변경 시 값을 올린다 |
| sessionId | string | - | (필수, 기본값 없음) | 촬영 세션 식별자. 로그·디버깅·MLLM 검증 결과 추적용 |
| status | string | `ok` \| `needs_clarification` \| `failed` | "ok" | 파싱 결과 상태 |
| subjectType | string | `person` \| `object` \| `landscape` | "person" | 피사체 종류 |
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

이 값들은 `ai/slot_parser.py`의 `FRAMING_KEYWORDS`와 `TargetSpec` 기본값 정의를 기준으로 한다. 새 키워드가 추가되면 이 문서도 함께 갱신한다.

## 예시 JSON

```json
{
  "schemaVersion": "0.1",
  "sessionId": "sess_20260812_001",
  "status": "ok",
  "subjectType": "person",
  "subjectCount": 2,
  "framing": "closeup",
  "rawText": "친구 두 명이랑 같이 나오게, 얼굴 크게 찍어줘",
  "confidence": 0.9,
  "source": "clova"
}
```

## 변경 이력
- 0.1: 초안 작성 — subjectType/subjectCount/framing/rawText/confidence
- 0.1(수정1): schemaVersion/sessionId/status/source 필드 추가
- 0.1(수정2): 각 필드 허용값·기본값 명시 (subjectType/framing enum 반영)
