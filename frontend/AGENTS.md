# 프론트엔드 작업 안내

이 파일은 `frontend/**`에만 적용된다. 제품 범위는 [P0 명세](../docs/P0-spec.md), 요청·응답·인증·CSRF는 [API 명세](../docs/API.md)가 정본이다.

## API 호출

- 모든 백엔드 호출은 `src/api.js`를 경유한다. 화면과 컴포넌트에서 `fetch`를 직접 호출하지 않는다.
- 상태 변경은 `src/api.js`의 `mutate`로 `GET /api/auth/csrf` 응답의 `headerName`과 `token`을 전달한다. 로그인·로그아웃 뒤 토큰 무효화·재조회를 우회하지 않는다.
- 배포에서는 웹과 API를 같은 사이트에서 제공하고 상대 경로 `/api`를 사용한다. 다른 origin의 API를 브라우저에서 직접 호출해야 하면 [API 인증·세션·CSRF 계약](../docs/API.md#12-인증세션csrf), [ADR-0003의 same-site 결정](../docs/adr/auth/0003-p0-server-session-spring-security.md#결정)과 [ADR-0021의 배포 기준선](../docs/adr/platform/0021-p0-aws-ec2-rds-deployment-baseline.md#결정)을 먼저 확인한다. 필요한 cross-origin·배포 계약 변경이 승인되어 해당 정본에 반영되기 전에는 구현을 중단한다.
- 같은 사이트의 API 경로 접두사는 빌드 환경의 `VITE_API_BASE_PATH`로 지정한다. `/service`이면 `/api`를 `/service/api`로 보낸다.

## 실행과 산출물

- Vite 7이 요구하는 Node.js 20.19 이상 또는 22.12 이상을 사용한다. Node.js 21과 22.0~22.11은 사용하지 않는다.
- `npm install`과 `npm run dev`, `npm run build`, `npm run preview`를 사용한다. Windows PowerShell에서는 `npm`을 `npm.cmd`로 바꾼다.
- 개발 서버는 `/api`를 기본적으로 `http://localhost:8080`에 프록시한다. 다른 로컬 백엔드는 추적하지 않는 `.env.local`의 `VITE_API_PROXY_TARGET`으로 지정한다.
- `dist/`는 생성 산출물이며 추적하지 않는다.
