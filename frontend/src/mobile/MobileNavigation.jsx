import React from 'react';

const TABS = [
  { key: 'home', label: '홈', href: '#/home', d: 'M3.8 10.3 12 4l8.2 6.3v8.5a1.7 1.7 0 0 1-1.7 1.7H5.5a1.7 1.7 0 0 1-1.7-1.7zM9.5 20.5v-5.3h5v5.3' },
  { key: 'game', label: '게임', href: '#/game-list', d: 'M4.6 4.6h14.8v14.8H4.6zM9.4 9.4h.02M14.6 14.6h.02' },
  { key: 'find', label: '모임 찾기', href: '#/find', d: 'M10 4.6a3.6 3.6 0 1 1 0 7.2 3.6 3.6 0 0 1 0-7.2M3.6 20v-1.1A3.9 3.9 0 0 1 7.5 15h5a3.9 3.9 0 0 1 3.9 3.9V20M16.4 5.2a3.4 3.4 0 0 1 0 6.6M18 15.2a3.9 3.9 0 0 1 2.4 3.6V20' },
  { key: 'profile', label: '내정보', href: '#/profile', d: 'M12 4.4a3.7 3.7 0 1 1 0 7.4 3.7 3.7 0 0 1 0-7.4M5.2 20v-1.2A3.9 3.9 0 0 1 9.1 15h5.8a3.9 3.9 0 0 1 3.9 3.9V20', requiresAuth: true }
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
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d={tab.d} /></svg>
            <span>{tab.label}</span>
          </a>
        );
      })}
    </nav>
  );
}
