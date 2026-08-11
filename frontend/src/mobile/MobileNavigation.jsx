import React from 'react';

const TABS = [
  { key: 'home', label: '홈', href: '#/home' },
  { key: 'game', label: '게임', href: '#/game-list' },
  { key: 'find', label: '모임 찾기', href: '#/find' },
  { key: 'profile', label: '내정보', href: '#/profile', requiresAuth: true }
];

export function mobileTabForRoute(route) {
  if (route === 'game' || route === 'game-list') return 'game';
  if (['find', 'create', 'session'].includes(route)) return 'find';
  if (['my', 'edit', 'profile', 'auth', 'signup'].includes(route)) return 'profile';
  if (['chat', 'chats'].includes(route)) return null;
  return 'home';
}

function MobileTabIcon({ tab }) {
  if (tab === 'game') {
    return <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="4" /><path d="M8 9v4M6 11h4M16.5 10h.01M18 13h.01" /></svg>;
  }
  if (tab === 'find') {
    return <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="9" cy="8" r="3" /><circle cx="17" cy="9" r="2" /><path d="M3.5 19a5.5 5.5 0 0 1 11 0M14 18.5a4 4 0 0 1 6.5-3.1" /></svg>;
  }
  if (tab === 'profile') {
    return <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="8" r="3.5" /><path d="M5 20a7 7 0 0 1 14 0" /></svg>;
  }
  return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m3 10 9-7 9 7v10H3Z" /><path d="M9 20v-6h6v6" /></svg>;
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
            <MobileTabIcon tab={tab.key} />
            <span>{tab.label}</span>
          </a>
        );
      })}
    </nav>
  );
}
