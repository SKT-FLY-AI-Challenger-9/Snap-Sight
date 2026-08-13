# 타겟 스펙(Target Spec) 인터페이스 계약

STT/NLU(①)가 사용자 발화를 파싱해 생성하고, 백엔드 판정 로직(③)으로 전달하는 데이터 구조입니다.

## 필드 정의

| 필드 | 타입 | 설명 | 예시 |
| --- | --- | --- | --- |
| schemaVersion | string | 스키마 버전. 필드 변경 시 값을 올린다 | "0.1" |
| sessionId | string | 촬영 세션 식별자. 로그·디버깅·MLLM 검증 결과 추적용 | "sess_20260812_001" |
| status | string | 파싱 결과 상태. `ok`(정상) / `needs_clarification`(재질문 필요) / `failed`(파싱 실패) | "ok" |
| subjectType | string | 피사체 종류 | "person" |
| subjectCount | int \| null | 요청된 인원/개체 수 (본인 제외, 미지정 시 null) | 2 |
| framing | string | 프레이밍 스타일 | "closeup" |
| rawText | string | 원본 발화 텍스트 (디버깅/로그용, MLLM 컨텍스트로도 활용) | "친구 두 명이랑 같이 나오게 찍어줘" |
| confidence | float | 파싱 신뢰도 (0~1) | 0.9 |
| source | string | 사용된 STT 엔진 (`clova` / `elevenlabs` / `ondevice`) | "clova" |

## status 값 정의

- `ok`: 정상 파싱됨. 그대로 사용 가능.
- `needs_clarification`: confidence가 낮거나 필수 슬롯이 비어있어 재질문이 필요함.
- `failed`: STT 자체가 실패했거나 텍스트가 비어있음 (rawText가 빈 문자열일 수 있음).

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
- 0.1(수정): schemaVersion/sessionId/status/source 필드 추가
