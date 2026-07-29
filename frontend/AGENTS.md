# 프론트엔드 작업 안내

이 파일은 `frontend/**`에만 적용되는 작업 규칙이다. 제품 범위는 [P0 명세](../docs/P0-spec.md), 요청·응답과 인증·CSRF 계약은 [API 명세](../docs/API.md)를 정본으로 삼는다.

## API 호출

- 모든 백엔드 호출은 `src/api.js`를 경유한다. 화면과 컴포넌트에서 `fetch`를 직접 호출하지 않는다.
- 상태 변경 요청은 `src/api.js`의 `mutate` 경로를 사용해 `GET /api/auth/csrf` 응답의 `headerName`과 `token`을 전달한다. 로그인·로그아웃 뒤의 토큰 무효화와 재조회 흐름을 우회하지 않는다.
- 배포에서는 웹과 API를 같은 사이트에서 제공하고 상대 경로 `/api`를 사용한다. 다른 origin의 API를 브라우저에서 직접 호출하는 구조가 필요하면 먼저 API·배포 계약을 갱신한다.

## 실행과 산출물

- Vite 7이 요구하는 Node.js 20.19 이상 또는 22.12 이상을 사용한다. Node.js 21과 22.0~22.11은 사용하지 않는다.
- macOS·Linux에서는 `npm`, Windows PowerShell에서는 `npm.cmd`를 사용한다.

| 작업 | macOS·Linux | Windows PowerShell |
| --- | --- | --- |
| 의존성 설치 | `npm install` | `npm.cmd install` |
| 개발 서버 | `npm run dev` | `npm.cmd run dev` |
| 프로덕션 빌드 | `npm run build` | `npm.cmd run build` |
| 빌드 미리보기 | `npm run preview` | `npm.cmd run preview` |

- 개발 서버는 `/api`를 기본적으로 `http://localhost:8080`에 프록시한다. 다른 로컬 백엔드 포트는 추적하지 않는 `.env.local`의 `VITE_API_PROXY_TARGET`으로 지정한다.
- `dist/`는 생성 산출물이며 추적하지 않는다.
