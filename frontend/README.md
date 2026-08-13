# 알밤메이트 프론트엔드

알밤메이트 P0·P1 화면과 현재 제공 기능을 백엔드 API에 연결하고 P2 기능을 확장하는 React(Vite) 프론트엔드입니다. 구현 규칙과 실행 환경은 [프론트엔드 작업 안내](AGENTS.md), 새 기능의 현재 상태는 [P2 기능 상태](../docs/p2/README.md#기능별-현재-상태), 요청·응답·인증·CSRF 계약은 [API 명세](../docs/API.md)를 따릅니다.

## 실행

Node.js 버전, 운영체제별 npm 명령, 개발 프록시와 산출물 규칙은 [작업 안내의 실행과 산출물](AGENTS.md#실행과-산출물)에서 확인합니다. 기본 다중 인스턴스 백엔드·프론트엔드 실행은 [로컬 개발 환경 실행](../docs/guides/LOCAL_DEVELOPMENT.md)을 따릅니다.

## 구조

```
frontend/
├─ index.html          # 진입 HTML
├─ src/
│  ├─ api.js           # 공통 응답 봉투, 세션·CSRF와 현재 API 호출
│  ├─ main.jsx         # 화면, 해시 라우팅과 P0·P1 사용자 흐름
│  ├─ notification/    # 알림 조회·읽음 동기화·이동
│  ├─ *.test.{js,jsx}  # API와 화면 회귀 테스트
│  └─ styles.css       # 공통 스타일
├─ vite.config.js      # 개발 시 /api → Spring Boot 프록시
└─ assets/             # 로고 심볼, 폰트(Cafe24Ssurround)
```

## 현재 화면과 기능

- P0: 홈, 로그인·회원가입, 게임·모임 탐색, 게임·모임 상세, 모임 생성·수정, 내 모임과 프로필
- P1: 소셜 로그인·계정 연결, 게임 메타데이터 필터와 해 본 게임 표시, 웹 알림함·읽음 동기화, 모임 채팅 이력·전송·WebSocket 재연결

화면은 해시 라우팅(`#/home`, `#/game-list`, `#/session/:id`, `#/chat/:id` 등)으로 동작합니다. 이 목록은 탐색용 요약이며 정확한 제공 상태와 API 계약을 대신하지 않습니다.

## 확인

```sh
npm test
npm run build
```

Windows PowerShell에서는 `npm` 대신 `npm.cmd`를 사용합니다.
