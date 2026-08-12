# Pull Request Guide

Pull Request는 작업 브랜치에서 `develop` 브랜치로 생성합니다.

## PR Title

형식:

```text
[SCOPE] type: 작업 요약
```

예시:

```text
[FE] feature: 로그인 페이지 구현
[BE] feature: 사용자 API 구현
[BE] fix: 토큰 갱신 오류 수정
```

## Scopes

- `fe`: 프론트엔드 작업
- `be`: 백엔드 작업
- `common`: 공통 설정, 문서, GitHub 템플릿, 레포 관리 작업

PR 제목에서는 scope를 대문자로 작성합니다.
작업 요약은 한글로 작성합니다.

- `FE`: 프론트엔드 작업
- `BE`: 백엔드 작업
- `COMMON`: 공통 설정, 문서, GitHub 템플릿, 레포 관리 작업

## PR Content

PR 본문은 아래 형식을 사용합니다.

```md
## 변경 사항
-

## 테스트
-

## 관련 이슈
-

## 참고 사항
-
```

- `관련 이슈`에는 `close #이슈번호` 형식으로 작성합니다.
- `참고 사항`은 리뷰어가 알아야 할 내용이 있을 때 작성합니다.

## Checklist

- 대상 브랜치가 `develop`인지 확인했습니다.
- 관련 Issue를 연결했습니다.
- 직접 테스트했습니다.
- 불필요한 로그, 주석, 임시 파일을 제거했습니다.
- 문서 수정이 필요한 경우 함께 반영했습니다.

## Merge Rule

- PR 대상 브랜치는 `develop`으로 설정합니다.
- 최소 1명 이상의 리뷰 후 머지합니다.
- 충돌이 있으면 작업자가 직접 해결합니다.
- `Squash and merge` 방식으로 `develop`에 머지합니다.
