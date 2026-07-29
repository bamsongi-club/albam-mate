# 알밤메이트 프론트엔드 (P0)

알밤메이트 P0 범위를 구현한 React(Vite) 프론트엔드입니다. 구현 규칙, HTTP 계약 라우팅과 실행 환경은
[프론트엔드 작업 안내](AGENTS.md)를 따릅니다.

## 실행

Node.js 버전, 운영체제별 npm 명령, 개발 프록시와 산출물 규칙은
[작업 안내의 실행과 산출물](AGENTS.md#실행과-산출물)에서 확인합니다. 백엔드 실행과 데이터베이스 준비는
[로컬 PostgreSQL 개발 환경](../docs/COMMANDS.md#로컬-postgresql-개발-환경)을 따릅니다.

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

P0 범위·규칙은 [프론트엔드 작업 안내](AGENTS.md)가 연결하는 정본을 따릅니다.
