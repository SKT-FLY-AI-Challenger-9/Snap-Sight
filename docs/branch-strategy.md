# Branch Strategy

## Main Branches

### `main`

- 배포 가능한 안정 버전만 유지합니다.
- 직접 커밋하지 않습니다.
- `develop`에서 충분히 검증된 변경사항만 머지합니다.

### `develop`

- 기본 개발 브랜치입니다.
- 모든 기능 브랜치는 `develop`에서 생성합니다.
- 모든 Pull Request의 대상 브랜치는 기본적으로 `develop`입니다.

## Working Branches

작업 브랜치는 반드시 Issue 생성 후 만듭니다.

브랜치 이름 형식:

```text
type/scope/#issue-number-short-description
```

예시:

```text
feature/fe/#12-login-page
feature/be/#13-user-api
fix/be/#18-token-refresh
docs/common/#21-update-readme
refactor/be/#30-api-response
```

## Scopes

- `fe`: 프론트엔드 작업
- `be`: 백엔드 작업
- `common`: 공통 설정, 문서, GitHub 템플릿, 레포 관리 작업

## Branch Types

- `feature`: 새로운 기능
- `fix`: 버그 수정
- `docs`: 문서 수정
- `refactor`: 동작 변경 없는 코드 개선
- `test`: 테스트 추가/수정
- `chore`: 설정, 빌드, 패키지 관리 등

## Recommended Flow

```bash
git checkout develop
git pull origin develop
git checkout -b feature/fe/#12-login-page
```

작업 완료 후:

```bash
git push origin feature/fe/#12-login-page
```

그 다음 GitHub에서 `develop`을 대상으로 Pull Request를 생성합니다.
