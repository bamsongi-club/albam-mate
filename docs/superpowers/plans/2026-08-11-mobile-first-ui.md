# Mobile-first UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 기능과 해시 라우트를 유지한 채, 모바일에서 독립적인 탐색·내 모임·채팅 경험을 제공한다.

**Architecture:** 새 `mobile` 컴포넌트는 모바일의 정보 구조만 담당하고 기존 `api.js`, 해시 라우팅, 권한 검사는 재사용한다. `main.jsx`는 컴포넌트를 배선하고, `styles.css`는 767px 이하에서 모바일 전용 셸을 활성화한다. 데스크톱 구조는 유지하되 공용 토큰만 공유한다.

**Tech Stack:** React 19, Vite 7, Vitest 4, React Testing Library, CSS custom properties.

## Global Constraints

- 모든 API 호출은 `frontend/src/api.js`의 `api` 객체를 통한다.
- 모바일 breakpoint는 `max-width: 767px`이며, 주요 터치 대상은 최소 44px이다.
- 하단 탭은 `홈`, `게임`, `내 모임`, `내정보` 네 개이며 전체 채팅은 상단 아이콘에 남긴다.
- 모바일 홈의 내 모임 요약은 `joined`와 `hosted`의 기존 `getMyRooms` 결과만 사용한다. 새 서버 API·정렬 계약은 만들지 않는다.
- 전체 Vitest 병렬 실행은 기준 `develop`에서도 worker timeout으로 실패한다. 이번 변경의 TDD 명령에는 `--maxWorkers=1`을 붙인다.
- 새로운 생산 코드마다 먼저 실패하는 테스트를 관찰하고, 해당 테스트가 통과한 뒤에만 다음 동작으로 간다.

---

### Task 1: 모바일 하단 탐색 컴포넌트

**Files:**
- Create: `frontend/src/mobile/MobileNavigation.jsx`
- Create: `frontend/src/mobile/MobileNavigation.test.jsx`

**Interfaces:**
- Consumes: `route: string`, `authenticated: boolean`
- Produces: `mobileTabForRoute(route): 'home' | 'game' | 'my' | 'profile'` and `MobileBottomNavigation` React component

- [ ] **Step 1: Write the failing test**

```jsx
import { render, screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import { MobileBottomNavigation } from './MobileNavigation';

it('로그인 사용자의 현재 게임 화면을 활성화하고 네 개의 모바일 탭을 제공한다', () => {
  render(<MobileBottomNavigation route="game-list" authenticated />);

  expect(screen.getByRole('navigation', { name: '모바일 주요 메뉴' })).toBeTruthy();
  expect(screen.getByRole('link', { name: '게임' }).getAttribute('aria-current')).toBe('page');
  expect(screen.getByRole('link', { name: '내 모임' }).getAttribute('href')).toBe('#/my');
  expect(screen.getAllByRole('link')).toHaveLength(4);
});

it('비로그인 사용자의 내 모임과 내정보 탭을 로그인 진입점으로 보낸다', () => {
  render(<MobileBottomNavigation route="home" authenticated={false} />);

  expect(screen.getByRole('link', { name: '내 모임' }).getAttribute('href')).toBe('#/auth');
  expect(screen.getByRole('link', { name: '내정보' }).getAttribute('href')).toBe('#/auth');
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- src/mobile/MobileNavigation.test.jsx --maxWorkers=1`

Expected: FAIL because `MobileNavigation.jsx` does not exist.

- [ ] **Step 3: Write minimal implementation**

```jsx
export const mobileTabForRoute = (route) => {
  if (route === 'game' || route === 'game-list') return 'game';
  if (route === 'my' || route === 'create' || route === 'edit' || route === 'session' || route === 'chat' || route === 'chats') return 'my';
  if (route === 'profile' || route === 'auth' || route === 'signup') return 'profile';
  return 'home';
};

export function MobileBottomNavigation({ route, authenticated }) {
  const current = mobileTabForRoute(route);
  const tabs = [
    { key: 'home', label: '홈', href: '#/home', icon: '⌂' },
    { key: 'game', label: '게임', href: '#/game-list', icon: '🎲' },
    { key: 'my', label: '내 모임', href: authenticated ? '#/my' : '#/auth', icon: '♟' },
    { key: 'profile', label: '내정보', href: authenticated ? '#/profile' : '#/auth', icon: '♙' }
  ];

  return (
    <nav className="mobile-bottom-nav" aria-label="모바일 주요 메뉴">
      {tabs.map((tab) => (
        <a key={tab.key} href={tab.href} aria-current={current === tab.key ? 'page' : undefined}>
          <span aria-hidden="true">{tab.icon}</span>
          <span>{tab.label}</span>
        </a>
      ))}
    </nav>
  );
}
```

각 링크의 접근 가능한 이름은 레이블만 남긴다. 실제 아이콘은 구현 시 기존 `SectionIcon`과 동일한 선형 SVG로 바꿔도 되지만, 탭의 key·label·href·접근성 계약은 위 코드와 같아야 한다.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- src/mobile/MobileNavigation.test.jsx --maxWorkers=1`

Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/mobile/MobileNavigation.jsx frontend/src/mobile/MobileNavigation.test.jsx
git commit -m "feat: 모바일 하단 탭 추가"
```

### Task 2: 다음 내 모임 홈 패널

**Files:**
- Create: `frontend/src/mobile/MobileHomePanel.jsx`
- Create: `frontend/src/mobile/MobileHomePanel.test.jsx`

**Interfaces:**
- Consumes: `dataVersion: number`, `api.getMyRooms({ role, page, size }, signal)`
- Produces: `nextUpcomingRoom(rooms, now): Room | null` and `MobileHomePanel` React component

- [ ] **Step 1: Write the failing test**

```jsx
it('참가·개설 모임 중 미래 시작 시각이 가장 이른 모임을 홈에 표시한다', async () => {
  getMyRooms.mockImplementation(({ role }) => Promise.resolve({
    content: role === 'joined' ? [laterRoom] : [nextRoom],
    page: 0, totalPages: 1
  }));
  render(<MobileHomePanel dataVersion={0} />);

  await waitFor(() => expect(screen.getByText(nextRoom.title)).toBeTruthy());
  expect(screen.getByRole('link', { name: '채팅' }).getAttribute('href')).toBe(`#/chat/${nextRoom.id}`);
  expect(getMyRooms).toHaveBeenCalledWith({ role: 'joined', page: 0, size: 100 }, expect.any(AbortSignal));
  expect(getMyRooms).toHaveBeenCalledWith({ role: 'hosted', page: 0, size: 100 }, expect.any(AbortSignal));
});

it('예정된 내 모임이 없으면 탐색과 만들기 CTA를 보인다', async () => {
  getMyRooms.mockResolvedValue({ content: [], page: 0, totalPages: 0 });
  render(<MobileHomePanel dataVersion={0} />);

  await waitFor(() => expect(screen.getByText('예정된 모임이 없어요.')).toBeTruthy());
  expect(screen.getByRole('link', { name: '모임 찾아보기' }).getAttribute('href')).toBe('#/find');
  expect(screen.getByRole('link', { name: '모임 만들기' }).getAttribute('href')).toBe('#/create');
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- src/mobile/MobileHomePanel.test.jsx --maxWorkers=1`

Expected: FAIL because `MobileHomePanel.jsx` does not exist.

- [ ] **Step 3: Write minimal implementation**

```jsx
export function nextUpcomingRoom(rooms, now = Date.now()) {
  return rooms
    .filter((room) => Number.isFinite(Date.parse(room.startsAt)) && Date.parse(room.startsAt) > now)
    .sort((left, right) => Date.parse(left.startsAt) - Date.parse(right.startsAt))[0] || null;
}

export function MobileHomePanel({ dataVersion }) {
  const { data, loading, error } = useRequest((signal) => Promise.all([
    api.getMyRooms({ role: 'joined', page: 0, size: 100 }, signal),
    api.getMyRooms({ role: 'hosted', page: 0, size: 100 }, signal)
  ]), [dataVersion]);
  const seen = new Set();
  const rooms = (data || [])
    .flatMap((page) => page.content || [])
    .map(normalizeRoom)
    .filter((room) => (seen.has(room.id) ? false : (seen.add(room.id), true)));
  const nextRoom = nextUpcomingRoom(
    rooms.filter((room) => room.status === 'RECRUITING' || room.status === 'CLOSED')
  );

  if (error) return null;
  if (loading && !data) return <section className="mobile-home-panel" aria-live="polite">내 모임을 확인하고 있어요.</section>;
  if (!nextRoom) {
    return <section className="mobile-home-panel"><p>예정된 모임이 없어요.</p><a href="#/find">모임 찾아보기</a><a href="#/create">모임 만들기</a></section>;
  }
  return <section className="mobile-home-panel"><p>다음 내 모임</p><h2>{nextRoom.title}</h2><p>{formatUpcomingStartsAt(nextRoom.startsAt)} · {nextRoom.place || nextRoom.region || '장소 미정'}</p><a href={'#/chat/' + nextRoom.id}>채팅</a><a href={'#/session/' + nextRoom.id}>상세 보기</a></section>;
}
```

위 코드에 쓰는 `formatUpcomingStartsAt(startsAt)`는 `startsAt`을 `오늘 HH:MM` 또는 `M/D(요일) HH:MM`으로 변환하는 이 파일 내부 helper로 둔다. 로딩 중에는 짧은 상태 문구만 보이고, API 오류는 탐색 홈을 가리지 않도록 패널만 숨긴다.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- src/mobile/MobileHomePanel.test.jsx --maxWorkers=1`

Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/mobile/MobileHomePanel.jsx frontend/src/mobile/MobileHomePanel.test.jsx
git commit -m "feat: 모바일 홈 내 모임 요약 추가"
```

### Task 3: 기존 앱에 모바일 셸 배선

**Files:**
- Modify: `frontend/src/main.jsx:1-20` (모바일 컴포넌트 import)
- Modify: `frontend/src/main.jsx:399-456` (기존 Header에 모바일 화면 제목 hook 추가)
- Modify: `frontend/src/main.jsx:561-590` (로그인한 홈에 `MobileHomePanel` 배치)
- Modify: `frontend/src/main.jsx:2180-2205` (`MobileBottomNavigation` 배치)
- Create: `frontend/src/mobile/MobileAppIntegration.test.jsx`

**Interfaces:**
- Consumes: `App`, `Header`, `HomeView`, `MobileBottomNavigation`, `MobileHomePanel`
- Produces: 모바일 viewport에서 사용할 별도 DOM 훅 `mobile-page-title`, `mobile-bottom-nav`, `mobile-home-panel`

- [ ] **Step 1: Write the failing test**

```jsx
it('로그인한 홈에서 모바일 내 모임 패널과 네 개 탭을 함께 배선한다', async () => {
  window.location.hash = '#/home';
  getMyProfile.mockResolvedValue({ id: 1, nickname: '테스터' });
  getMyRooms.mockResolvedValue({ content: [], page: 0, totalPages: 0 });
  render(<App />);

  await waitFor(() => expect(screen.getByLabelText('모바일 주요 메뉴')).toBeTruthy());
  expect(screen.getByText('예정된 모임이 없어요.')).toBeTruthy();
  expect(document.querySelector('.mobile-page-title')?.textContent).toBe('홈');
});
```

모든 `App` 의존 API(`getMyProfile`, `getSocialProviders`, `getNotifications`, `getUnreadNotificationCount`, `getRooms`, `getGames`, `getMyRooms`)는 이 테스트에서 성공 응답으로 mock한다.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- src/mobile/MobileAppIntegration.test.jsx --maxWorkers=1`

Expected: FAIL because `App`은 아직 모바일 컴포넌트를 렌더링하지 않는다.

- [ ] **Step 3: Write minimal implementation**

```jsx
// HomeView는 me를 받아 로그인 상태에서만 기존 hero보다 앞에 요약 패널을 렌더링한다.
function HomeView({ me, onBrowsePeople, onSearchGame, dataVersion }) {
  const [input, setInput] = useState('');
  const { data, loading, error } = useRequest(
    (signal) => api.getRooms({ type: 'PERSON_FOCUSED', page: 0, size: 1 }, signal),
    [dataVersion]
  );
  const { data: gameData, loading: gameLoading } = useRequest(
    (signal) => api.getGames({ page: 0, size: 1 }, signal),
    [dataVersion]
  );
  const personCount = data?.totalElements ?? 0;
  const gameCount = gameData?.totalElements ?? 0;
  return (
    <>
      {me && <MobileHomePanel dataVersion={dataVersion} />}
      <section className="card hero">
        <h1>오늘, 보드게임 한 판 어때요? 🎲</h1>
        <p>게임을 먼저 고르거나, 함께할 사람부터 찾아 모임을 만들 수 있어요.</p>
        <form className="inline-search hero-search" onSubmit={(event) => { event.preventDefault(); onSearchGame(input.trim()); }}>
          <label className="hint" htmlFor="home-q" style={{ position: 'absolute', left: -9999 }}>게임 이름 검색</label>
          <input id="home-q" value={input} onChange={(event) => setInput(event.target.value)} placeholder="게임 이름으로 검색" />
          <button type="submit" aria-label="검색">검색</button>
        </form>
        <div className="dual">
          <a className="entry gamefirst" href="#/game-list"><span className="big" aria-hidden="true">🎲</span><h3>게임부터 찾기</h3><p>하고 싶은 게임을 검색하고, 그 게임의 공개 모임을 찾아보세요.</p><span className="sub">{gameLoading ? '게임 불러오는 중…' : '게임 ' + gameCount + '개 둘러보기 →'}</span></a>
          <a className="entry peoplefirst" href="#/find" onClick={onBrowsePeople}><span className="big" aria-hidden="true">🙌</span><h3>사람부터 만나기</h3><p>게임이 아직 정해지지 않아도 괜찮아요. 제목으로 원하는 모임을 찾아보세요.</p><span className="sub">{loading ? '공개 모임 불러오는 중…' : '공개 모임 ' + personCount + '개 →'}</span></a>
        </div>
        {error && <p className="hint" style={{ marginTop: 16 }}>공개 모임 수를 불러오지 못했어요: {error}</p>}
      </section>
    </>
  );
}

// App의 기본 홈 분기는 아래처럼 me를 전달한다.
content = <HomeView me={me} onBrowsePeople={handleBrowsePeople} onSearchGame={handleSearchGame} dataVersion={dataVersion} />;

// App의 main 다음에 항상 렌더링하고 CSS로 desktop에서는 숨긴다.
<MobileBottomNavigation route={route} authenticated={Boolean(me)} />
```

`Header`에는 `mobilePageTitle(route)` helper(`home: 홈`, `find: 모임 찾기`, `game/game-list: 게임`, `session: 모임`, `create: 모임 만들기`, `edit: 모임 수정`, `my: 내 모임`, `chat/chats: 채팅`, `profile: 내정보`, `auth: 로그인`, `signup: 회원가입`) 결과를 넣는 `<span className="mobile-page-title">`를 추가한다. 현재 로그인 사용자에게만 보이는 `#/chats` 링크는 모바일에서도 상단 우측의 전체 채팅 진입점으로 유지한다. 기존 데스크톱 GNB, 알림 패널, 채팅·프로필 해시 링크 및 모든 route 분기는 삭제하거나 이름을 바꾸지 않는다.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- src/mobile/MobileAppIntegration.test.jsx --maxWorkers=1`

Expected: PASS, 1 test.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/main.jsx frontend/src/mobile/MobileAppIntegration.test.jsx
git commit -m "feat: 모바일 셸을 앱에 연결"
```

### Task 4: 모바일 전용 스타일과 실제 viewport 검증

**Files:**
- Modify: `frontend/src/styles.css:8-31` (공용 디자인 토큰)
- Modify: `frontend/src/styles.css:544-604` (기존 좁은 화면 규칙을 767px 모바일 셸 규칙으로 대체·보완)

**Interfaces:**
- Consumes: `mobile-page-title`, `mobile-bottom-nav`, `mobile-home-panel`, 기존 `.chat-*`, `.filter-*`, `.game-picker-*` 클래스
- Produces: 767px 이하의 고정 하단 탭, safe-area 여백, 한 열 카드, 모바일 채팅·필터 레이아웃

- [ ] **Step 1: Confirm the DOM contract is already green**

Run: `cd frontend && npm test -- src/mobile/MobileAppIntegration.test.jsx --maxWorkers=1`

Expected: PASS. Task 3의 실패-통과 루프가 `.mobile-bottom-nav`, `.mobile-home-panel`, `.mobile-page-title` DOM 계약을 이미 고정했으므로 CSS는 이 계약만 소비한다.

- [ ] **Step 2: Write minimal implementation**

```css
:root {
  --bg: #f7f5ee;
  --card: #fffdfa;
  --ink: #191814;
  --accent: #191814;
  --green: #1d765d;
  --line: #e6dfd0;
}

@media (max-width: 767px) {
  .mobile-bottom-nav { bottom: 0; display: grid; grid-template-columns: repeat(4, 1fr); position: fixed; padding-bottom: env(safe-area-inset-bottom); }
  main { padding-bottom: calc(84px + env(safe-area-inset-bottom)); }
  .site-footer, .scroll-top-btn { display: none; }
  .chat-main { height: calc(100dvh - 150px); }
}
```

나머지 규칙은 모바일 상단 앱바, 한 열 모임 카드, 두 열 게임 카드, 44px 조작, full-width 필터, bottom-sheet 게임 선택, 카드·말풍선·입력창의 시안 색상과 radius를 맞춘다. 기존 560px 규칙 중 상충하는 값을 767px 블록 안에서 명시적으로 덮어쓴다.

- [ ] **Step 3: Run automated and visual verification**

Run:

```bash
cd frontend
npm test -- src/mobile/MobileNavigation.test.jsx src/mobile/MobileHomePanel.test.jsx src/mobile/MobileAppIntegration.test.jsx --maxWorkers=1
npm run build
```

그 뒤 Vite preview에서 390px, 767px, 1180px 폭을 확인한다. 390px에서는 하단 탭이 가려지지 않고, 홈·게임·내 모임·내정보·채팅의 조작이 44px 이상이며, 1180px에서는 기존 헤더와 목록 구조가 유지되어야 한다.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/styles.css
git commit -m "feat: 모바일 우선 화면 스타일 적용"
```

### Task 5: 변경 범위 최종 확인

**Files:**
- Verify: `frontend/src/mobile/MobileNavigation.test.jsx`
- Verify: `frontend/src/mobile/MobileHomePanel.test.jsx`
- Verify: `frontend/src/mobile/MobileAppIntegration.test.jsx`
- Verify: `frontend/src/ChatEntry.test.jsx`

**Interfaces:**
- Consumes: 앞선 모든 모바일 컴포넌트와 기존 채팅 route
- Produces: 모바일 새 동작과 기존 채팅 진입이 공존한다는 검증 결과

- [ ] **Step 1: Run targeted regression tests**

Run:

```bash
cd frontend
npm test -- src/mobile/MobileNavigation.test.jsx src/mobile/MobileHomePanel.test.jsx src/mobile/MobileAppIntegration.test.jsx src/ChatEntry.test.jsx --maxWorkers=1
```

Expected: PASS. 채팅 테스트가 기준 병렬 실패와 무관하게 단일 worker에서 통과해야 한다.

- [ ] **Step 2: Inspect the final diff**

Run: `git diff --check origin/develop...HEAD && git diff --check && git status --short`

Expected: 공백 오류 없음, 모바일 파일·`main.jsx`·`styles.css`·문서만 변경됨.

- [ ] **Step 3: Return defects to the owning task**

최종 검증에서 결함이 보이면 이 단계에서 임시로 고치지 않는다. 결함이 발생한 Task의 실패 테스트를 먼저 재현하고, 해당 Task의 최소 구현·통과 검증을 다시 수행한다.
