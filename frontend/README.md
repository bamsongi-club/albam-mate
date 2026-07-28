# 알밤메이트 프론트엔드 (P0)

알밤메이트 P0 범위를 구현한 React(Vite) 프론트엔드입니다. 백엔드 API 연동 전 단계로,
목업 데이터(`src/main.jsx` 내 `GAMES`·`SESSIONS`)로 P0 핵심 경험을 클릭형으로 확인할 수 있습니다.

## 실행

Node 18+ 필요.

```bash
cd frontend
npm install
npm run dev
```

- `npm run dev` — 개발 서버 (기본 http://localhost:5173)
- `npm run build` — 프로덕션 빌드 (`dist/`)
- `npm run preview` — 빌드 결과 미리보기

## 구조

```
frontend/
├─ index.html          # 진입 HTML
├─ src/
│  ├─ main.jsx         # 전체 화면·라우팅·목업 데이터·비즈니스 규칙 (P0)
│  └─ styles.css       # 스타일
└─ assets/             # 로고 심볼, 폰트(Cafe24Ssurround)
```

## P0 화면

홈 · 게임 찾기 · 사람 중심 모임 · 게임 상세 · 모임 상세 · 모임 만들기 · 모임 수정 · 내 모임 · 프로필

해시 라우팅(`#/home`, `#/games`, `#/session/:id` …)으로 동작합니다.

## 참고

P0 범위·규칙은 저장소 루트의 [docs/P0-spec.md](../docs/P0-spec.md), [docs/API.md](../docs/API.md)를 따릅니다.
