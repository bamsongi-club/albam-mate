import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../api';
import { App } from '../main';

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  cleanup();
  window.location.hash = '';
});

function page(content) {
  return { content, page: 0, size: 100, totalElements: content.length, totalPages: 1 };
}

const GAMES = [
  { id: 1, name: '카탄', supportedPlayerCount: '3-4인', estimatedPlayTime: '75분' },
  { id: 2, name: '윙스팬', supportedPlayerCount: '1-5인', estimatedPlayTime: '70분' },
  { id: 3, name: '아줄', supportedPlayerCount: '2-4인', estimatedPlayTime: '35분' },
  { id: 4, name: '스플렌더', supportedPlayerCount: '2-4인', estimatedPlayTime: '30분' }
];

function stubApi({ authenticated = true } = {}) {
  const profile = { id: 1, nickname: '테스터', email: 'tester@example.com', profileImageUrl: null };
  vi.spyOn(api, 'getMyProfile').mockImplementation(() => (
    authenticated ? Promise.resolve(profile) : Promise.reject(Object.assign(new Error('401'), { status: 401 }))
  ));
  vi.spyOn(api, 'getSocialProviders').mockResolvedValue([]);
  vi.spyOn(api, 'getNotifications').mockResolvedValue(page([]));
  vi.spyOn(api, 'getUnreadNotificationCount').mockResolvedValue({ unreadCount: 0 });
  vi.spyOn(api, 'getRooms').mockResolvedValue(page([]));
  vi.spyOn(api, 'getMyRooms').mockResolvedValue(page([]));
  vi.spyOn(api, 'getGameRankings').mockResolvedValue({ overall: [], pastWeek: [] });
  vi.spyOn(api, 'getGames').mockResolvedValue(page(GAMES));
  vi.spyOn(api, 'getGame').mockResolvedValue({
    id: 101, name: '카탄', supportedPlayerCount: '3-4인', estimatedPlayTime: '75분',
    categories: [], themes: [], mechanisms: []
  });
  vi.spyOn(api, 'getAssistantConsent').mockResolvedValue({
    status: 'GRANTED',
    provider: 'OPENAI',
    consentVersion: 'AI-01-CONSENT-V1',
    policyVersion: 'OPENAI-2026-08',
    policyUrl: 'https://example.com/provider-policy',
    retentionMode: 'default-30d',
    store: false,
    grantedAt: '2026-08-20T09:00:00Z',
    revokedAt: null
  });
  vi.spyOn(api, 'getActiveAssistantDraft').mockResolvedValue(null);
  vi.spyOn(api, 'recommendAssistant').mockResolvedValue({
    state: 'NEEDS_INPUT',
    conditions: { categories: [], mechanisms: [], themes: [] },
    missingFields: ['GAME_STYLE'],
    candidates: []
  });
}

async function renderApp(hash) {
  vi.stubGlobal('scrollTo', vi.fn());
  window.location.hash = hash;
  const view = render(<App />);
  await act(async () => {});
  return view;
}

function toastText() {
  return document.getElementById('toast').textContent;
}

async function press(name) {
  await act(async () => { screen.getByRole('button', { name }).click(); });
}

beforeEach(() => stubApi());

describe('P2 시안 진입', () => {
  it('상단 화면에서만 알밤봇 FAB을 띄운다', async () => {
    await renderApp('#/home');
    await waitFor(() => expect(screen.getByRole('link', { name: '알밤봇 열기' })).toBeTruthy());

    await act(async () => { window.location.hash = '#/chats'; });

    await waitFor(() => expect(screen.queryByRole('link', { name: '알밤봇 열기' })).toBeNull());
  });

  it('비로그인 사용자는 매칭에 들어가지 못하고 로그인 안내를 받는다', async () => {
    vi.restoreAllMocks();
    stubApi({ authenticated: false });

    await renderApp('#/match');

    await waitFor(() => expect(screen.getByRole('heading', { name: '로그인이 필요해요' })).toBeTruthy());
    expect(screen.getByText('온라인 매칭을 쓰려면 로그인해주세요.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '매칭 시작하기' })).toBeNull();
  });

  it('비로그인 사용자는 알밤봇에도 들어가지 못한다', async () => {
    vi.restoreAllMocks();
    stubApi({ authenticated: false });

    await renderApp('#/assistant');

    await waitFor(() => expect(screen.getByText('알밤봇을 쓰려면 로그인해주세요.')).toBeTruthy());
  });
});

describe('P2 시안은 서버가 할 일을 실행하지 않는다', () => {
  it('매칭 시작하기는 준비 중임을 알리고 화면을 바꾸지 않는다', async () => {
    await renderApp('#/match');
    await waitFor(() => expect(screen.getByRole('button', { name: '매칭 시작하기' })).toBeTruthy());

    await press('매칭 시작하기');

    expect(toastText()).toBe('아직 준비 중인 기능이에요.');
    expect(screen.getByRole('button', { name: '매칭 시작하기' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '매칭 취소' })).toBeNull();
  });

  it('알밤봇은 서버 추천 API를 호출하고 추가 질문을 표시한다', async () => {
    await renderApp('#/assistant');
    await waitFor(() => expect(screen.getByText('같이 할 게임을 찾아볼까요? 인원, 시간, 게임 분위기 중 아는 것부터 편하게 알려주세요.')).toBeTruthy());

    await act(async () => { fireEvent.change(screen.getByLabelText('알밤봇에게 묻기'), { target: { value: '게임 추천해줘' } }); });
    await press('전송');

    await waitFor(() => expect(screen.getByText('어떤 분위기나 게임 스타일을 원하는지 알려주세요.')).toBeTruthy());
    expect(api.recommendAssistant).toHaveBeenCalledWith('게임 추천해줘', null);
  });

  it('게임 상세를 다녀오면 추천을 다시 호출하지 않고 선택한 직접 입력 상태를 복원한다', async () => {
    api.recommendAssistant.mockResolvedValue({
      state: 'RECOMMENDED',
      conditions: {
        categories: ['COOPERATIVE'], mechanisms: [], themes: [], playerCount: null, startsAt: null,
        region: '홍대', experienceLevel: 'BEGINNER_WELCOME'
      },
      missingFields: [],
      candidates: [{ id: 101, name: '카탄', imageUrl: null, description: '정식 카탈로그 설명' }]
    });

    await renderApp('#/assistant');
    await waitFor(() => expect(screen.getByLabelText('알밤봇에게 묻기')).toBeTruthy());
    await act(async () => { fireEvent.change(screen.getByLabelText('알밤봇에게 묻기'), { target: { value: '협력 게임 추천해줘' } }); });
    await press('전송');
    await waitFor(() => expect(screen.getByRole('link', { name: '카탄 상세 보기' })).toBeTruthy());
    await press('이 게임으로 모임 만들기');
    await press('내가 직접 채우기');
    fireEvent.change(screen.getByLabelText('소개 (선택)'), { target: { value: '복원할 소개' } });

    await act(async () => { screen.getByRole('link', { name: '카탄 상세 보기' }).click(); });
    await waitFor(() => expect(screen.getByRole('heading', { name: '카탄' })).toBeTruthy());
    await act(async () => { window.location.hash = '#/assistant'; });

    await waitFor(() => expect(screen.getByLabelText('소개 (선택)').value).toBe('복원할 소개'));
    expect(api.recommendAssistant).toHaveBeenCalledTimes(1);
  });

  it('일반 경로를 거친 새 assistant 세션은 후보와 직접 입력 상태를 복원하지 않는다', async () => {
    api.recommendAssistant.mockResolvedValue({
      state: 'RECOMMENDED',
      conditions: {
        categories: ['COOPERATIVE'], mechanisms: [], themes: [], playerCount: null, startsAt: null,
        region: '홍대', experienceLevel: 'BEGINNER_WELCOME'
      },
      missingFields: [],
      candidates: [{ id: 101, name: '카탄', imageUrl: null, description: '정식 카탈로그 설명' }]
    });

    await renderApp('#/assistant');
    await waitFor(() => expect(screen.getByLabelText('알밤봇에게 묻기')).toBeTruthy());
    await act(async () => { fireEvent.change(screen.getByLabelText('알밤봇에게 묻기'), { target: { value: '협력 게임 추천해줘' } }); });
    await press('전송');
    await waitFor(() => expect(screen.getByRole('link', { name: '카탄 상세 보기' })).toBeTruthy());
    await press('이 게임으로 모임 만들기');
    await press('내가 직접 채우기');
    fireEvent.change(screen.getByLabelText('소개 (선택)'), { target: { value: '버려야 할 소개' } });

    await act(async () => { window.location.hash = '#/home'; });
    await waitFor(() => expect(screen.getByRole('link', { name: '알밤봇 열기' })).toBeTruthy());
    await act(async () => { window.location.hash = '#/assistant'; });

    await waitFor(() => expect(screen.getByLabelText('알밤봇에게 묻기')).toBeTruthy());
    expect(screen.queryByRole('link', { name: '카탄 상세 보기' })).toBeNull();
    expect(screen.queryByRole('form', { name: 'AI 초안 만들기' })).toBeNull();
    expect(screen.queryByDisplayValue('버려야 할 소개')).toBeNull();
    expect(api.recommendAssistant).toHaveBeenCalledTimes(1);
  });

  it('온라인 방의 표는 참가자 수와 같고, 정하기와 아레나 열기는 준비 중임을 알린다', async () => {
    await renderApp('#/online-room');
    await waitFor(() => expect(screen.getByRole('button', { name: /카탄\s*\d+표/ })).toBeTruthy());

    const votes = screen.getAllByText(/^\d+표$/).map((node) => Number(node.textContent.replace('표', '')));
    expect(votes.reduce((sum, count) => sum + count, 0)).toBe(4);

    await press(/으로 정하기$/);
    expect(toastText()).toBe('아직 준비 중인 기능이에요.');

    await press(/보드게임아레나에서 열기$/);
    expect(toastText()).toBe('아직 준비 중인 기능이에요.');
  });
});

describe('P2 시안 화면 이동', () => {
  it('진행 단계는 주소로 확인하고 이동만 실제로 동작한다', async () => {
    await renderApp('#/match/searching');
    await waitFor(() => expect(screen.getByRole('button', { name: '매칭 취소' })).toBeTruthy());

    await act(async () => { window.location.hash = '#/match/matched'; });
    await waitFor(() => expect(screen.getByRole('heading', { name: /모였어요/ })).toBeTruthy());

    await press(/온라인 방 들어가기/);
    await waitFor(() => expect(window.location.hash).toBe('#/online-room'));
  });

  it('실패 화면은 다시 시도와 오프라인 모임 보기를 함께 준다', async () => {
    await renderApp('#/match/failed');

    await waitFor(() => expect(screen.getByRole('heading', { name: '지금은 사람이 모이지 않았어요' })).toBeTruthy());
    await press('오프라인 모임 보기');
    await waitFor(() => expect(window.location.hash).toBe('#/find'));
  });
});
