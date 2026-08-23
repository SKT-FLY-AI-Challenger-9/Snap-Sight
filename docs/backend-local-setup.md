# 백엔드 로컬 실행 · 실기기 접속 가이드

에뮬레이터가 아닌 **실기기(폰)에서 백엔드에 접속**하기 위한 설정을 정리한다. 관련 이슈: #47, #42

> 개발용 구성이다. 인증도 HTTPS도 없으므로 신뢰할 수 있는 로컬 네트워크에서만 사용한다.

촬영 데이터는 기본 24시간 뒤 정리 대상이 되고, 서버 시작 시와 기본 15분 간격으로 만료
세션을 제한된 개수씩 삭제한다. 값은 `.env`의 `SNAPSIGHT_CAPTURE_TTL_SECONDS`,
`SNAPSIGHT_CAPTURE_CLEANUP_INTERVAL_SECONDS`, `SNAPSIGHT_CAPTURE_CLEANUP_BATCH_SIZE`로 조정한다.
TTL `0`은 자동 정리를 끄므로 로컬 디버깅 외에는 사용하지 않는 편이 안전하다.

## 서버 실행

```bash
python -m backend.main
```

`0.0.0.0:8000`에 바인딩되어 같은 네트워크의 다른 기기에서도 접속할 수 있다. `SERVER_HOST`·`SERVER_PORT` 환경변수로 재정의할 수 있다.

코드 변경 시 자동 재시작이 필요하면 uvicorn을 직접 쓴다. 이때 `--host`를 빼면 `127.0.0.1`에만 열려 **실기기에서 접속되지 않으니** 주의한다.

```bash
uvicorn backend.main:app --host 0.0.0.0 --port 8000 --reload
```

`.env`가 없거나 `ANTHROPIC_API_KEY`가 비어 있으면 서버 기동 시 명확한 에러와 함께 종료된다.

## 실기기 접속

에뮬레이터는 `10.0.2.2`로 호스트 PC에 접근하지만 실기기는 그 방법이 없다. PC와 폰을 **같은 와이파이**에 두고 PC의 LAN IP를 직접 지정해야 한다.

1. PC의 LAN IP 확인

   ```bash
   ipconfig getifaddr en0      # macOS (유선이면 en1)
   hostname -I                 # Linux
   ```

2. **폰 브라우저**에서 `http://<확인한 IP>:8000/docs`를 먼저 열어본다. FastAPI 문서 화면이 뜨면 연결에 문제가 없다는 뜻이다 — 여기서 막히면 앱 문제가 아니라 네트워크 문제다.

3. debug 앱의 백엔드 주소를 설정 화면에서 `http://<확인한 IP>:8000`으로 지정하거나,
   `-PBACKEND_BASE_URL=http://<확인한 IP>:8000`으로 빌드한다. release 앱은 HTTPS만 허용한다.

LAN IP는 고정이 아니다. 와이파이를 바꾸거나 공유기를 재부팅하면 달라지므로 그때마다 다시 확인한다.

### 접속이 안 될 때

- 폰이 LTE로 붙어 있지 않은지 확인한다. PC와 **같은 와이파이**여야 한다
- macOS 방화벽이 켜져 있으면 `python`의 수신 연결을 허용한다 (시스템 설정 → 네트워크 → 방화벽 → 옵션)
- 회사·학교 와이파이는 기기 간 통신을 차단하는 경우가 많다. 폰 핫스팟에 PC를 연결하는 쪽이 빠르다
- 평문 HTTP이므로 Android의 cleartext 허용 설정이 필요하다

### 서버 쪽 점검

```bash
lsof -nP -iTCP:8000 -sTCP:LISTEN
```

`TCP *:8000 (LISTEN)`으로 나와야 한다. `127.0.0.1:8000`이면 모든 인터페이스에 열리지 않은 것이다.

```bash
curl -o /dev/null -w "%{http_code}\n" http://<LAN IP>:8000/docs
```

PC에서 실행하되 LAN 주소로 접속하므로 루프백이 아니다. `200`이면 서버는 정상이고, 그래도 폰에서 안 되면 방화벽이나 공유기 문제다.

## 통합 결과 조회 폴링

`POST /api/capture/frames`는 서버가 발급한 `capture_revision`을 즉시 반환한다. Android는
`GET /api/capture/{session_id}/metadata` 하나를 폴링해 짧은 설명·상세 설명·검색 라벨·
`final_frame_id`를 함께 받고, 후보가 선택됐으면 같은 revision으로 `/final-frame`을 내려받는다.
`/result`와 `/description`은 구버전 호환용으로 유지된다.

| 응답 | 의미 | 앱 동작 |
|---|---|---|
| `404` | 업로드된 적 없는 세션 | 재시도하지 않는다 |
| `200` + `status: "pending"` | 선택/설명 진행 중 | `retry_after_seconds`와 지수 백오프를 함께 적용 |
| `200` + `status: "failed"` | 파이프라인 종료 실패 | 즉시 폴링 종료 |
| `200` + `status: "done"` | 완료 | revision 검증 후 설명·라벨·최종 프레임 사용 |

소요 시간 실측치(#37 수동 검증):

| 경로 | 소요 시간 |
|---|---|
| 규칙 기반으로 발화가 파싱된 세션 | 약 6초 |
| 규칙이 실패해 LLM 폴백까지 탄 세션 | 약 12초 |

Android 통합 메타데이터 폴러의 전체 타임아웃은 120초다. 429·5xx·전송 오류만 재시도하고,
401/403을 포함한 다른 4xx 및 알 수 없는 status는 설정/계약 오류로 즉시 종료한다.

## 배포 인증·TLS

- 서버에 `SNAPSIGHT_API_TOKEN`을 설정했다면 Android 빌드에도 같은 값을
  `-PSNAPSIGHT_API_TOKEN=...`로 주입한다.
- release 백엔드 주소는
  `-PSNAPSIGHT_RELEASE_BACKEND_BASE_URL=https://api.example.com`으로 주입한다.
- release는 저장돼 있던 `http://` 주소도 복원하지 않는다. 로컬 평문 접속은 debug 빌드에만 허용한다.
