# 알밤메이트 프론트엔드 (P0)

알밤메이트 P0 범위를 구현한 React(Vite) 프론트엔드입니다. `docs/API.md`의 HTTP 계약을 사용해
게임·방·참가·프로필·인증 API를 호출합니다. 로그인 세션 쿠키는 브라우저가 유지하고, 상태 변경 요청은
`GET /api/auth/csrf`로 받은 토큰을 API가 지정한 헤더에 함께 보냅니다.

## 실행

Node 20.19+ 또는 22.12+ 필요 (Vite 7의 `^20.19.0 || >=22.12.0` 요구사항). Node 18·21.x·22.0~22.11에서는 실행되지 않습니다.

```bash
cd frontend
npm.cmd install
npm.cmd run dev
```

- Windows PowerShell에서는 `npm.cmd run dev`, `npm.cmd run build`, `npm.cmd run preview`를 사용합니다. macOS·Linux 셸에서는 `npm`을 사용합니다.
- `npm.cmd run dev` — 개발 서버 (기본 http://localhost:5173)
- `npm.cmd run build` — 프로덕션 빌드 (`dist/`)
- `npm.cmd run preview` — 빌드 결과 미리보기

개발 서버는 기본적으로 `/api` 요청을 `http://localhost:8080`의 Spring Boot 서버로 프록시합니다.
[로컬 PostgreSQL 개발 환경](../docs/COMMANDS.md#로컬-postgresql-개발-환경)의 DB 준비와 환경 변수 주입을 마친 같은 PowerShell에서 백엔드를 먼저 실행하세요.

```powershell
# 저장소 루트 (로컬 PostgreSQL 개발 환경 준비 후)
.\gradlew.bat bootRun --args='--spring.profiles.active=local'

# 별도 터미널
cd frontend
npm.cmd run dev
```

백엔드 포트가 다르면 `frontend/.env.local`에 아래처럼 설정합니다. 이 파일은 로컬 환경 전용이며
커밋하지 않습니다.

```text
VITE_API_PROXY_TARGET=http://localhost:8081
```

배포에서는 웹과 API를 같은 사이트에서 제공하고, 프론트는 기본값처럼 상대 경로 `/api`를 사용합니다.
같은 사이트 안에서 API 앞에 경로 접두사가 있으면 `VITE_API_BASE_PATH`로 설정할 수 있습니다. 다른 origin의
API URL을 브라우저에서 직접 호출하는 방식은 P0의 same-site 배포 계약에 맞지 않습니다.

## 구조

```
frontend/
├─ index.html          # 진입 HTML
├─ src/
│  ├─ api.js           # 공통 응답 봉투, 세션 쿠키, CSRF 토큰, P0 API 호출
│  ├─ main.jsx         # 전체 화면·해시 라우팅·API 결과 표시 (P0)
│  └─ styles.css       # 스타일
├─ vite.config.js      # 개발 시 /api → Spring Boot 프록시
└─ assets/             # 로고 심볼, 폰트(Cafe24Ssurround)
```

## P0 화면

홈 · 로그인·회원가입 · 게임 찾기 · 사람 중심 모임 · 게임 상세 · 모임 상세 · 모임 만들기 · 모임 수정 · 내 모임 · 프로필

해시 라우팅(`#/home`, `#/games`, `#/session/:id` …)으로 동작합니다.

## 참고

P0 범위·규칙은 저장소 루트의 [docs/P0-spec.md](../docs/P0-spec.md), [docs/API.md](../docs/API.md)를 따릅니다.
