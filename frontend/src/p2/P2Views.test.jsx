import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../api';
import { App } from '../main';

afterEach(() => {
  vi.useRealTimers();
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

const OPERATION_TIME = '2026-08-24T00:00:00+09:00';
const NO_CURRENT_MATCH = { operationTime: OPERATION_TIME, state: null, request: null, proposal: null, preparing: null, chat: null };

class FakeWebSocket {
  static instances = [];

  constructor(url) {
    this.url = url;
    this.close = vi.fn(() => this.onclose?.({ code: 1000 }));
    FakeWebSocket.instances.push(this);
  }

  open() {
    this.onopen?.();
  }

  message(payload) {
    this.onmessage?.({ data: JSON.stringify(payload) });
  }

  drop(code = 1006) {
    this.onclose?.({ code });
  }
}

function useFakeWebSocket() {
  FakeWebSocket.instances = [];
  vi.stubGlobal('WebSocket', FakeWebSocket);
  return FakeWebSocket.instances;
}

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
  vi.spyOn(api, 'getCurrentMatch').mockResolvedValue(NO_CURRENT_MATCH);
}

async function renderApp(hash) {
  vi.stubGlobal('scrollTo', vi.fn());
  window.location.hash = hash;
  const view = render(<App />);
  await act(async () => {});
  return view;
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((nextResolve, nextReject) => {
    resolve = nextResolve;
    reject = nextReject;
  });
  return { promise, resolve, reject };
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
});

describe('MATCH-01 실시간 파티 매칭', () => {
  it('현재 상태 조회가 일시 실패해도 마지막 상태로 폴링을 계속한다', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    const waiting = {
      operationTime: OPERATION_TIME,
      state: 'WAITING',
      request: { minPlayers: 2, maxPlayers: 2, queuedAt: OPERATION_TIME },
      proposal: null,
      preparing: null,
      chat: null
    };
    api.getCurrentMatch
      .mockResolvedValueOnce(waiting)
      .mockRejectedValueOnce(new Error('일시적인 네트워크 오류'))
      .mockResolvedValue(waiting);

    await renderApp('#/match');
    await waitFor(() => expect(screen.getByText(/사람을 찾고 있어요/)).toBeTruthy());

    await act(async () => { await vi.advanceTimersByTimeAsync(3500); });
    expect(api.getCurrentMatch).toHaveBeenCalledTimes(2);
    expect(screen.getByText(/사람을 찾고 있어요/)).toBeTruthy();

    await act(async () => { await vi.advanceTimersByTimeAsync(3500); });
    expect(api.getCurrentMatch).toHaveBeenCalledTimes(3);
  });

  it('원하는 인원으로 매칭을 요청하면 대기 화면으로 바뀐다', async () => {
    const waiting = {
      operationTime: OPERATION_TIME,
      state: 'WAITING',
      request: { minPlayers: 2, maxPlayers: 6, queuedAt: OPERATION_TIME },
      proposal: null,
      preparing: null,
      chat: null
    };
    api.getCurrentMatch.mockResolvedValueOnce(NO_CURRENT_MATCH).mockResolvedValue(waiting);
    vi.spyOn(api, 'createMatchRequest').mockResolvedValue(waiting);

    await renderApp('#/match');
    await waitFor(() => expect(screen.getByRole('button', { name: '매칭 시작하기' })).toBeTruthy());
    const maxTrigger = screen.getAllByRole('button', { name: /^\d명$/ })[1];
    await act(async () => { maxTrigger.click(); });
    await act(async () => { screen.getByRole('button', { name: '6명' }).click(); });

    await press('매칭 시작하기');

    await waitFor(() => expect(screen.getByText(/사람을 찾고 있어요/)).toBeTruthy());
    expect(api.createMatchRequest).toHaveBeenCalledWith({ minPlayers: 2, maxPlayers: 6 }, expect.any(String));
    expect(screen.getByRole('button', { name: '매칭 취소' })).toBeTruthy();
  });

  it('대기 화면에서 매칭 취소를 누르면 요청을 취소한다', async () => {
    const waiting = {
      operationTime: OPERATION_TIME,
      state: 'WAITING',
      request: { minPlayers: 2, maxPlayers: 2, queuedAt: OPERATION_TIME },
      proposal: null,
      preparing: null,
      chat: null
    };
    api.getCurrentMatch.mockResolvedValueOnce(waiting).mockResolvedValue(NO_CURRENT_MATCH);
    vi.spyOn(api, 'cancelMatchRequest').mockResolvedValue(NO_CURRENT_MATCH);

    await renderApp('#/match');
    await waitFor(() => expect(screen.getByRole('button', { name: '매칭 취소' })).toBeTruthy());

    await press('매칭 취소');

    expect(api.cancelMatchRequest).toHaveBeenCalled();
    await waitFor(() => expect(screen.getByRole('button', { name: '매칭 시작하기' })).toBeTruthy());
  });

  it('제안 화면은 30초 응답 기한과 세 가지 응답만 보여주고 닉네임을 노출하지 않는다', async () => {
    const proposed = {
      operationTime: OPERATION_TIME,
      state: 'PROPOSED',
      request: null,
      proposal: {
        proposalId: 5,
        partySize: 3,
        members: [{ profileImageUrl: null }, { profileImageUrl: null }, { profileImageUrl: null }],
        respondBy: new Date(Date.now() + 30000).toISOString(),
        myResponse: 'PENDING'
      },
      preparing: null,
      chat: null
    };
    api.getCurrentMatch.mockResolvedValue(proposed);

    await renderApp('#/match');

    await waitFor(() => expect(screen.getByRole('button', { name: '수락' })).toBeTruthy());
    expect(screen.getByRole('button', { name: '건너뛰고 다시 기다리기' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '매칭 취소' })).toBeTruthy();
    expect(screen.getByText(/초 안에 답해 주세요/)).toBeTruthy();
    expect(screen.queryByText('지현')).toBeNull();
    expect(screen.queryByText('테스터')).toBeNull();
  });

  it('수락은 새 Idempotency-Key와 함께 서버에 응답을 보낸다', async () => {
    const proposed = {
      operationTime: OPERATION_TIME,
      state: 'PROPOSED',
      request: null,
      proposal: {
        proposalId: 7,
        partySize: 2,
        members: [{ profileImageUrl: null }, { profileImageUrl: null }],
        respondBy: new Date(Date.now() + 30000).toISOString(),
        myResponse: 'PENDING'
      },
      preparing: null,
      chat: null
    };
    const accepted = { ...proposed, proposal: { ...proposed.proposal, myResponse: 'ACCEPTED' } };
    api.getCurrentMatch.mockResolvedValueOnce(proposed).mockResolvedValue(accepted);
    vi.spyOn(api, 'respondToMatchProposal').mockResolvedValue(accepted);

    await renderApp('#/match');
    await waitFor(() => expect(screen.getByRole('button', { name: '수락' })).toBeTruthy());

    await press('수락');

    expect(api.respondToMatchProposal).toHaveBeenCalledWith(7, 'ACCEPT', expect.any(String));
    await waitFor(() => expect(screen.getByText('다른 인원을 기다리는 중이에요.')).toBeTruthy());
  });

  it('채팅 준비 화면은 진행 표시만 보여주고 버튼을 두지 않는다', async () => {
    const preparing = {
      operationTime: OPERATION_TIME,
      state: 'PREPARING',
      request: null,
      proposal: null,
      preparing: {
        preparingStartedAt: OPERATION_TIME,
        prepareUntil: OPERATION_TIME,
        members: [
          { nickname: '밤돌이', profileImageUrl: null, isMine: true },
          { nickname: '주사위굴러', profileImageUrl: null, isMine: false }
        ]
      },
      chat: null
    };
    api.getCurrentMatch.mockResolvedValue(preparing);

    await renderApp('#/match');

    await waitFor(() => expect(screen.getByText(/채팅방을 열고/)).toBeTruthy());
    expect(screen.getByText('밤돌이')).toBeTruthy();
    expect(screen.getByText('주사위굴러')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '수락' })).toBeNull();
    expect(screen.queryByRole('button', { name: '매칭 취소' })).toBeNull();
    expect(screen.queryByRole('button', { name: '건너뛰고 다시 기다리기' })).toBeNull();
  });

  it('상태가 ACTIVE가 되면 매칭 채팅으로 자동 이동한다', async () => {
    const active = {
      operationTime: OPERATION_TIME,
      state: 'ACTIVE',
      request: null,
      proposal: null,
      preparing: null,
      chat: {
        partyId: 9,
        members: [
          { participantRef: 'me', nickname: '테스터', profileImageUrl: null, isMine: true },
          { participantRef: 'other', nickname: '민경', profileImageUrl: null, isMine: false }
        ],
        chatOpenedAt: OPERATION_TIME,
        closesAt: new Date(Date.now() + 3 * 60 * 60 * 1000).toISOString(),
        historyPath: '/api/matches/parties/9/chat/messages',
        sendPath: '/api/matches/parties/9/chat/messages',
        webSocketPath: '/api/matches/parties/9/chat/ws'
      }
    };
    api.getCurrentMatch.mockResolvedValue(active);
    vi.spyOn(api, 'getMatchChatMessages').mockResolvedValue({ messages: [], nextBeforeMessageId: null, hasNext: false });
    useFakeWebSocket();

    await renderApp('#/match');

    await waitFor(() => expect(window.location.hash).toBe('#/match-chat'));
    await waitFor(() => expect(screen.getByText('온라인 매칭 · 2명')).toBeTruthy());
  });

  it('채팅 이력을 받은 최신 messageId 이후에 WebSocket을 구독한다', async () => {
    const active = {
      operationTime: OPERATION_TIME,
      state: 'ACTIVE',
      request: null,
      proposal: null,
      preparing: null,
      chat: {
        partyId: 9,
        members: [
          { participantRef: 'me', nickname: '테스터', profileImageUrl: null, isMine: true },
          { participantRef: 'other', nickname: '민경', profileImageUrl: null, isMine: false }
        ],
        chatOpenedAt: OPERATION_TIME,
        closesAt: new Date(Date.now() + 3 * 60 * 60 * 1000).toISOString(),
        historyPath: '/api/matches/parties/9/chat/messages',
        sendPath: '/api/matches/parties/9/chat/messages',
        webSocketPath: '/api/matches/parties/9/chat/ws'
      }
    };
    const history = deferred();
    api.getCurrentMatch.mockResolvedValue(active);
    vi.spyOn(api, 'getMatchChatMessages').mockReturnValue(history.promise);
    const sockets = useFakeWebSocket();

    await renderApp('#/match-chat');
    await waitFor(() => expect(screen.getByText('온라인 매칭 · 2명')).toBeTruthy());
    expect(sockets).toHaveLength(0);

    await act(async () => {
      history.resolve({
        messages: [{
          messageId: 4,
          partyId: 9,
          type: 'SYSTEM',
          content: '채팅방이 열렸어요.',
          sender: null
        }],
        nextBeforeMessageId: null,
        hasNext: false
      });
      await history.promise;
    });

    await waitFor(() => expect(sockets).toHaveLength(1));
    expect(sockets[0].url).toContain('afterMessageId=4');
  });

  it('WebSocket 재연결 때 마지막 messageId를 cursor로 유지하고 중복 이벤트를 한 번만 표시한다', async () => {
    const active = {
      operationTime: OPERATION_TIME,
      state: 'ACTIVE',
      request: null,
      proposal: null,
      preparing: null,
      chat: {
        partyId: 9,
        members: [
          { participantRef: 'me', nickname: '테스터', profileImageUrl: null, isMine: true },
          { participantRef: 'other', nickname: '민경', profileImageUrl: null, isMine: false }
        ],
        chatOpenedAt: OPERATION_TIME,
        closesAt: new Date(Date.now() + 3 * 60 * 60 * 1000).toISOString(),
        historyPath: '/api/matches/parties/9/chat/messages',
        sendPath: '/api/matches/parties/9/chat/messages',
        webSocketPath: '/api/matches/parties/9/chat/ws'
      }
    };
    const firstMessage = {
      messageId: 1,
      partyId: 9,
      type: 'USER',
      content: '먼저 온 메시지',
      sender: { participantRef: 'other', nickname: '민경' },
      isMine: false,
      createdAt: OPERATION_TIME
    };
    const secondMessage = {
      ...firstMessage,
      messageId: 2,
      content: '좋아요'
    };
    api.getCurrentMatch.mockResolvedValue(active);
    vi.spyOn(api, 'getMatchChatMessages').mockResolvedValue({ messages: [firstMessage], nextBeforeMessageId: null, hasNext: false });
    const sockets = useFakeWebSocket();

    await renderApp('#/match-chat');
    await waitFor(() => expect(screen.getByText('온라인 매칭 · 2명')).toBeTruthy());
    await waitFor(() => expect(sockets).toHaveLength(1));
    const event = { eventId: 2, type: 'MESSAGE_CREATED', message: secondMessage };
    await act(async () => { sockets[0].message(event); });
    await waitFor(() => expect(screen.getByText('좋아요')).toBeTruthy());

    sockets[0].drop();
    await waitFor(() => expect(sockets).toHaveLength(2));
    expect(sockets[1].url).toContain('afterMessageId=2');

    await act(async () => { sockets[1].message(event); });
    expect(screen.getAllByText('좋아요')).toHaveLength(1);
  });

  it('채팅 만료 시 현재 상태를 다시 조회하고 입력과 소켓 재연결을 중단한다', async () => {
    vi.useFakeTimers();
    const active = {
      operationTime: OPERATION_TIME,
      state: 'ACTIVE',
      request: null,
      proposal: null,
      preparing: null,
      chat: {
        partyId: 9,
        members: [{ participantRef: 'me', nickname: '테스터', profileImageUrl: null, isMine: true }],
        chatOpenedAt: OPERATION_TIME,
        closesAt: new Date(Date.now() + 1000).toISOString(),
        historyPath: '/api/matches/parties/9/chat/messages',
        sendPath: '/api/matches/parties/9/chat/messages',
        webSocketPath: '/api/matches/parties/9/chat/ws'
      }
    };
    const closeCheck = deferred();
    api.getCurrentMatch.mockResolvedValueOnce(active).mockReturnValueOnce(closeCheck.promise);
    vi.spyOn(api, 'getMatchChatMessages').mockResolvedValue({ messages: [], nextBeforeMessageId: null, hasNext: false });
    const sockets = useFakeWebSocket();

    await renderApp('#/match-chat');
    await act(async () => {});
    expect(screen.getByText('온라인 매칭 · 1명')).toBeTruthy();
    expect(sockets).toHaveLength(1);

    await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
    expect(api.getCurrentMatch).toHaveBeenCalledTimes(2);
    expect(screen.getByLabelText('메시지').disabled).toBe(true);

    sockets[0].drop();
    await act(async () => { await vi.advanceTimersByTimeAsync(6000); });
    expect(sockets).toHaveLength(1);

    await act(async () => {
      closeCheck.resolve(NO_CURRENT_MATCH);
      await closeCheck.promise;
    });
    expect(window.location.hash).toBe('#/match');
  });

  it('매칭 채팅에서 메시지를 보내고 실시간으로 받은 메시지를 표시한다', async () => {
    const active = {
      operationTime: OPERATION_TIME,
      state: 'ACTIVE',
      request: null,
      proposal: null,
      preparing: null,
      chat: {
        partyId: 9,
        members: [
          { participantRef: 'me', nickname: '테스터', profileImageUrl: null, isMine: true },
          { participantRef: 'other', nickname: '민경', profileImageUrl: null, isMine: false }
        ],
        chatOpenedAt: OPERATION_TIME,
        closesAt: new Date(Date.now() + 3 * 60 * 60 * 1000).toISOString(),
        historyPath: '/api/matches/parties/9/chat/messages',
        sendPath: '/api/matches/parties/9/chat/messages',
        webSocketPath: '/api/matches/parties/9/chat/ws'
      }
    };
    api.getCurrentMatch.mockResolvedValue(active);
    vi.spyOn(api, 'getMatchChatMessages').mockResolvedValue({ messages: [], nextBeforeMessageId: null, hasNext: false });
    vi.spyOn(api, 'sendMatchChatMessage').mockResolvedValue({
      messageId: 1, partyId: 9, type: 'USER', clientMessageId: 'x', sender: { participantRef: 'me', nickname: '테스터' },
      isMine: true, content: '같이 해요', createdAt: OPERATION_TIME
    });
    const sockets = useFakeWebSocket();

    await renderApp('#/match-chat');
    await waitFor(() => expect(screen.getByText('온라인 매칭 · 2명')).toBeTruthy());
    await act(async () => { sockets[0]?.open(); });

    await act(async () => { fireEvent.change(screen.getByLabelText('메시지'), { target: { value: '같이 해요' } }); });
    await act(async () => { fireEvent.submit(screen.getByLabelText('메시지').closest('form')); });

    await waitFor(() => expect(screen.getByText('같이 해요')).toBeTruthy());
    expect(api.sendMatchChatMessage).toHaveBeenCalledWith(9, { clientMessageId: expect.any(String), content: '같이 해요' });

    await act(async () => {
      sockets[0]?.message({
        eventId: 2,
        type: 'MESSAGE_CREATED',
        message: {
          messageId: 2, partyId: 9, type: 'USER', clientMessageId: 'y', sender: { participantRef: 'other', nickname: '민경' },
          isMine: false, content: '좋아요', createdAt: OPERATION_TIME
        }
      });
    });

    await waitFor(() => expect(screen.getByText('좋아요')).toBeTruthy());
  });

  it('나가기를 확인하면 파티를 나가고 매칭 화면으로 돌아간다', async () => {
    const active = {
      operationTime: OPERATION_TIME,
      state: 'ACTIVE',
      request: null,
      proposal: null,
      preparing: null,
      chat: {
        partyId: 9,
        members: [{ participantRef: 'me', nickname: '테스터', profileImageUrl: null, isMine: true }],
        chatOpenedAt: OPERATION_TIME,
        closesAt: new Date(Date.now() + 3 * 60 * 60 * 1000).toISOString(),
        historyPath: '/api/matches/parties/9/chat/messages',
        sendPath: '/api/matches/parties/9/chat/messages',
        webSocketPath: '/api/matches/parties/9/chat/ws'
      }
    };
    api.getCurrentMatch.mockResolvedValueOnce(active).mockResolvedValue(NO_CURRENT_MATCH);
    vi.spyOn(api, 'getMatchChatMessages').mockResolvedValue({ messages: [], nextBeforeMessageId: null, hasNext: false });
    vi.spyOn(api, 'leaveMatchParty').mockResolvedValue(NO_CURRENT_MATCH);
    vi.stubGlobal('confirm', vi.fn(() => true));
    useFakeWebSocket();

    await renderApp('#/match-chat');
    await waitFor(() => expect(screen.getByRole('button', { name: '참가자' })).toBeTruthy());
    await press('참가자');

    await waitFor(() => expect(screen.getByRole('button', { name: '채팅방 나가기' })).toBeTruthy());
    await press('채팅방 나가기');

    expect(api.leaveMatchParty).toHaveBeenCalledWith(9);
    await waitFor(() => expect(window.location.hash).toBe('#/match'));
  });

  it('차단 목록을 보여주고 차단을 해제할 수 있다', async () => {
    const blocks = page([
      { blockId: 3, blockedUser: { nickname: '철수', profileImageUrl: null }, blockedAt: OPERATION_TIME }
    ]);
    vi.spyOn(api, 'getMatchBlocks').mockResolvedValue(blocks);
    vi.spyOn(api, 'unblockMatchUser').mockResolvedValue({});

    await renderApp('#/match-blocks');

    await waitFor(() => expect(screen.getByText('철수')).toBeTruthy());

    await press('차단 해제');

    expect(api.unblockMatchUser).toHaveBeenCalledWith(3);
  });
});
