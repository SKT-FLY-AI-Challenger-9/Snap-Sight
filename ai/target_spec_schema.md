# 타겟 스펙(Target Spec) 인터페이스 계약

STT/NLU(①)가 사용자 발화를 파싱해 생성하고, 백엔드 판정 로직(③)으로 전달하는 데이터 구조입니다.

## 필드 정의

| 필드 | 타입 | 설명 | 예시 |
| --- | --- | --- | --- |
| subjectType | string | 피사체 종류 | "person" |
| subjectCount | int \| null | 요청된 인원/개체 수 (본인 제외, 미지정 시 null) | 2 |
| framing | string | 프레이밍 스타일 | "closeup" |
| rawText | string | 원본 발화 텍스트 (디버깅/로그용) | "친구 두 명이랑 같이 나오게 찍어줘" |
| confidence | float | 파싱 신뢰도 (0~1) | 0.9 |

## 예시 JSON

```json
{
  "subjectType": "person",
  "subjectCount": 2,
  "framing": "closeup",
  "rawText": "친구 두 명이랑 같이 나오게, 얼굴 크게 찍어줘",
  "confidence": 0.9
}
```

## 변경 이력
- 초안 작성 (①, 관련 이슈 참고)
