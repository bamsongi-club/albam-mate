import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from './api';
import { App } from './main';

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  cleanup();
  window.location.hash = '';
});

const me = { id: 1, nickname: '테스터', email: 'tester@example.com', profileImageUrl: null };

function page(content) {
  return { content, page: 0, size: 100, totalElements: content.length, totalPages: 0 };
}

function stubApp({ authenticated = true } = {}) {
  vi.spyOn(api, 'getMyProfile').mockImplementation(
    () => (authenticated ? Promise.resolve(me) : Promise.reject(Object.assign(new Error('no session'), { status: 401 })))
  );
  vi.spyOn(api, 'getSocialProviders').mockResolvedValue([]);
  vi.spyOn(api, 'getNotifications').mockResolvedValue(page([]));
  vi.spyOn(api, 'getUnreadNotificationCount').mockResolvedValue({ unreadCount: 0 });
  vi.spyOn(api, 'getRooms').mockResolvedValue(page([]));
  vi.spyOn(api, 'getGames').mockResolvedValue(page([]));
  vi.spyOn(api, 'getGameRankings').mockResolvedValue({ overall: [], pastWeek: [] });
  vi.spyOn(api, 'getMyRooms').mockResolvedValue(page([]));
}

async function renderAt(hash) {
  window.location.hash = hash;
  const view = render(<App />);
  await act(async () => {});
  return view;
}

const settle = () => act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });

// 내일 날짜여야 "시작 시간은 현재 시각 이후" 검증을 통과한다.
function tomorrow() {
  const date = new Date(Date.now() + 24 * 60 * 60 * 1000);
  return date.toISOString().slice(0, 10);
}

/**
 * #749·#754와 같은 화면 결함이 아니라 히스토리 결함이다. 완료한 화면이 스택에 남아 뒤로 가기가
 * 방금 끝낸 폼으로 되돌아갔다. jsdom은 history.length는 정확히 세지만 history.back()의 결과를
 * 반영하지 않으므로, 실제로 어느 화면으로 돌아가는지는 브라우저 검증이 담당한다.
 */
describe('#756 T1~T5 완료 이동은 히스토리에 쌓이지 않는다', () => {
  it('T1 모임을 개설하면 히스토리가 늘지 않고 상세로 바뀐다', async () => {
    stubApp();
    vi.spyOn(api, 'createRoom').mockResolvedValue({ id: 42 });
    vi.spyOn(api, 'getRoom').mockResolvedValue({
      id: 42, title: '새 모임', roomType: 'PERSON_FOCUSED', status: 'RECRUITING',
      startsAt: '2099-09-01T19:00:00+09:00', region: '홍대', place: '카페',
      experienceLevel: 'ANY', isRulemasterLed: false, participantCount: 1,
      recruitmentCapacity: 3, participants: [], myRole: 'HOST'
    });
    await renderAt('#/create');
    await waitFor(() => expect(screen.getByLabelText('제목')).toBeTruthy());

    // 기본값인 게임 중심은 게임 선택을 요구하므로, 게임 없이 열 수 있는 사람 중심으로 바꾼다.
    fireEvent.click(screen.getByRole('button', { name: /사람 중심/ }));
    fireEvent.change(screen.getByLabelText('제목'), { target: { value: '새 모임' } });
    fireEvent.change(screen.getByLabelText('장소'), { target: { value: '홍대 카페' } });
    fireEvent.change(screen.getByLabelText('날짜'), { target: { value: tomorrow() } });
    fireEvent.change(screen.getByLabelText('시작 시간'), { target: { value: '19:00' } });
    const lengthBeforeSubmit = window.history.length;

    fireEvent.click(screen.getByRole('button', { name: '모임 열기' }));

    await waitFor(() => expect(window.location.hash).toBe('#/session/42'));
    expect(api.createRoom).toHaveBeenCalled();
    // 완료 이동이 push였다면 여기서 1이 늘어 뒤로 가기가 #/create로 돌아간다.
    expect(window.history.length).toBe(lengthBeforeSubmit);
  });

  it('T2 모임 정보를 수정하면 히스토리가 늘지 않는다', async () => {
    stubApp();
    const editableRoom = {
      id: 42, title: '기존 모임', roomType: 'PERSON_FOCUSED', status: 'RECRUITING',
      startsAt: '2099-09-01T19:00:00+09:00', region: '홍대', place: '카페',
      experienceLevel: 'ANY', isRulemasterLed: false, participantCount: 1,
      recruitmentCapacity: 3, participants: [], myRole: 'HOST', description: ''
    };
    vi.spyOn(api, 'getRoom').mockResolvedValue(editableRoom);
    vi.spyOn(api, 'updateRoom').mockResolvedValue({ ...editableRoom, title: '고친 모임' });
    await renderAt('#/edit/42');
    await waitFor(() => expect(screen.getByLabelText('제목')).toBeTruthy());

    fireEvent.change(screen.getByLabelText('제목'), { target: { value: '고친 모임' } });
    const lengthBeforeSubmit = window.history.length;

    fireEvent.click(screen.getByRole('button', { name: /저장/ }));

    await waitFor(() => expect(api.updateRoom).toHaveBeenCalled());
    await waitFor(() => expect(window.location.hash).toBe('#/session/42'));
    expect(window.history.length).toBe(lengthBeforeSubmit);
  });

  it('T3 로그인하면 히스토리가 늘지 않고 홈으로 바뀐다', async () => {
    stubApp({ authenticated: false });
    vi.spyOn(api, 'login').mockResolvedValue(me);
    await renderAt('#/auth');
    await waitFor(() => expect(screen.getByRole('button', { name: '이메일로 로그인' })).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: '이메일로 로그인' }));
    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'tester@example.com' } });
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'password1234' } });
    const lengthBeforeSubmit = window.history.length;

    fireEvent.submit(screen.getByLabelText('비밀번호').closest('form'));

    await waitFor(() => expect(window.location.hash).toBe('#/home'));
    expect(window.history.length).toBe(lengthBeforeSubmit);
  });

  it('T4 로그아웃하면 히스토리가 늘지 않고 홈으로 바뀐다', async () => {
    stubApp();
    vi.spyOn(api, 'logout').mockResolvedValue({});
    await renderAt('#/profile');
    await waitFor(() => expect(screen.getByRole('button', { name: '로그아웃' })).toBeTruthy());
    const lengthBeforeLogout = window.history.length;

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }));

    await waitFor(() => expect(api.logout).toHaveBeenCalled());
    await waitFor(() => expect(window.location.hash).toBe('#/home'));
    expect(window.history.length).toBe(lengthBeforeLogout);
  });

  it('T5 화면 진입 이동은 그대로 히스토리에 쌓인다', async () => {
    stubApp();
    await renderAt('#/home');
    await settle();
    const lengthAtHome = window.history.length;

    await act(async () => { window.location.hash = '#/create'; });
    await settle();

    // 진입 이동까지 replace로 바꾸면 뒤로 가기가 아예 동작하지 않는다. 기본값은 push로 남아야 한다.
    expect(window.location.hash).toBe('#/create');
    expect(window.history.length).toBe(lengthAtHome + 1);
  });
});
