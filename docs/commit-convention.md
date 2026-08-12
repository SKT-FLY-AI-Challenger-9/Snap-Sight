# Commit Convention

커밋 메시지는 변경 의도를 짧고 명확하게 작성합니다.

## Format

```text
type(scope): 작업 요약
```

예시:

```text
feature(fe): 로그인 페이지 구현
feature(be): 사용자 API 구현
fix(be): 만료된 액세스 토큰 처리
docs(common): 브랜치 전략 문서 추가
refactor(be): 사용자 서비스 구조 개선
```

## Scopes

- `fe`: 프론트엔드 작업
- `be`: 백엔드 작업
- `common`: 공통 설정, 문서, GitHub 템플릿, 레포 관리 작업

## Types

- `feature`: 새로운 기능
- `fix`: 버그 수정
- `docs`: 문서 변경
- `style`: 포맷팅, 세미콜론, 공백 등 코드 의미 변경 없음
- `refactor`: 동작 변경 없는 코드 구조 개선
- `test`: 테스트 추가/수정
- `chore`: 빌드, 설정, 패키지 관리 등

## Rules

- 한 커밋에는 하나의 의도만 담습니다.
- `frontend/`와 `backend/`를 함께 수정한 경우에도 가능하면 커밋을 나눕니다.
- `type`과 `scope`는 정해진 영문 값을 사용하고, 작업 요약은 한글로 작성합니다.
- 작업 요약은 명령형 또는 간결한 설명형으로 작성합니다.
- 불필요하게 큰 커밋은 나누어 작성합니다.
- 관련 Issue가 있으면 커밋 본문 또는 PR 설명에 연결합니다.
