import React from 'react';

// Lucide 원본 마크업(<rect>/<circle> 조합)을 그대로 쓴다. 단일 path로 근사하면 시작·끝점이
// 만나는 모서리에 이음매가 생겨 깨진 것처럼 보인다.
const TAB_ICONS = {
  home: (
    <>
      <path d="M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8" />
      <path d="M3 10a2 2 0 0 1 .709-1.528l7-5.999a2 2 0 0 1 2.582 0l7 5.999A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
    </>
  ),
  game: (
    <>
      <rect x="3" y="3" width="18" height="18" rx="2" ry="2" />
      <path d="M8 8h.01" /><path d="M8 16h.01" /><path d="M12 12h.01" /><path d="M16 8h.01" /><path d="M16 16h.01" />
    </>
  ),
  find: (
    <>
      <path d="M18 21a8 8 0 0 0-16 0" />
      <circle cx="10" cy="8" r="5" />
      <path d="M22 20c0-3.37-2-6.5-4-8a5 5 0 0 0-.45-8.3" />
    </>
  ),
  profile: (
    <>
      <path d="M18 20a6 6 0 0 0-12 0" />
      <circle cx="12" cy="10" r="4" />
      <circle cx="12" cy="12" r="10" />
    </>
  )
};

const TABS = [
  { key: 'home', label: '홈', href: '#/home' },
  { key: 'game', label: '게임', href: '#/game-list' },
  { key: 'find', label: '모임 찾기', href: '#/find' },
  { key: 'profile', label: '내정보', href: '#/profile', requiresAuth: true }
];

// 탭바를 띄우는 상단 화면. 나머지는 하위 화면이라 뒤로가기로 돌아간다.
export const ROOT_ROUTES = ['home', 'game-list', 'find', 'profile'];

export function mobileTabForRoute(route) {
  if (route === 'game' || route === 'game-list' || route === 'game-rankings') return 'game';
  if (['find', 'create', 'session'].includes(route)) return 'find';
  if (['my', 'edit', 'profile', 'auth', 'signup', 'social-link'].includes(route)) return 'profile';
  if (['chat', 'chats'].includes(route)) return null;
  return 'home';
}

export function MobileBottomNavigation({ route, authenticated }) {
  const current = mobileTabForRoute(route);

  return (
    <nav className="mobile-bottom-nav" aria-label="모바일 주요 메뉴">
      {TABS.map((tab) => {
        const active = current === tab.key;
        const href = tab.requiresAuth && !authenticated ? '#/auth' : tab.href;
        return (
          <a
            key={tab.key}
            className={'mobile-bottom-nav-link' + (active ? ' on' : '')}
            href={href}
            aria-current={active ? 'page' : undefined}
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">{TAB_ICONS[tab.key]}</svg>
            <span>{tab.label}</span>
          </a>
        );
      })}
    </nav>
  );
}
