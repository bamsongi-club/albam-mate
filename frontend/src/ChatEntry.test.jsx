import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, api } from './api';
import {
  App,
  CHAT_SEND_REQUEST_DEADLINE_MS,
  CHAT_SEND_RESULT_UNKNOWN_MESSAGE,
  ChatRoomView,
  MyRoomsSection,
  SessionDetailView
} from './main';

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  cleanup();
});

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

  rawMessage(data) {
    this.onmessage?.({ data });
  }

  drop(code = 1006) {
    this.onclose?.({ code });
  }
}

class FakeIntersectionObserver {
  static instances = [];

  constructor(callback) {
    this.callback = callback;
    FakeIntersectionObserver.instances.push(this);
  }

  observe() {}

  disconnect() {}

  trigger(isIntersecting = true) {
    this.callback([{ isIntersecting }]);
  }
}

function myRoom(overrides) {
  return {
    id: 7,
    title: '홍대 보드게임 모임',
    roomType: 'PERSON_FOCUSED',
    status: 'RECRUITING',
    startsAt: '2026-09-01T19:00:00+09:00',
    region: '홍대',
    experienceLevel: 'ANY',
    isRulemasterLed: false,
    participantCount: 1,
    recruitmentCapacity: 3,
    remainingRecruitmentSeats: 3,
    game: null,
    myRole: 'HOST',
    participationStatus: null,
    chatAvailable: true,
    joinable: false,
    waitlistable: false,
    ...overrides
  };
}

function roomPage(content) {
  return { content, page: 0, size: 12, totalElements: content.length, totalPages: 1 };
}

// 탭별 요청이 서로 다른 방을 돌려줘야 활성 탭의 항목만 표시하는지 검증할 수 있다.
function renderMyRooms(room) {
  const tab = room.myRole === 'HOST' ? 'hosted' : 'joined';
  const otherRoom = myRoom({ id: 99, title: '다른 탭 모임', myRole: tab === 'hosted' ? 'JOINED' : 'HOST', chatAvailable: true });
  const getMyRooms = vi.spyOn(api, 'getMyRooms')
    .mockImplementation(({ role }) => Promise.resolve(roomPage(role === tab ? [room] : [otherRoom])));
  return { getMyRooms, tab, ...render(<MyRoomsSection myTab={tab} onMyTabChange={vi.fn()} dataVersion={0} />) };
}

const chatEntry = () => screen.queryByRole('link', { name: '💬 채팅 열기' });

describe('#427 내 모임 중복 채팅 진입 제거', () => {
  it('개설한 모임 목록에는 상세 진입만 두고 채팅 버튼을 반복 표시하지 않는다', async () => {
    const { getMyRooms } = renderMyRooms(myRoom({ myRole: 'HOST', chatAvailable: true }));

    await waitFor(() => expect(screen.getByText(/홍대 보드게임 모임/)).toBeTruthy());
    expect(getMyRooms).toHaveBeenCalledWith(expect.objectContaining({ role: 'hosted' }), expect.anything());
    expect(screen.queryByText(/다른 탭 모임/)).toBeNull();
    expect(chatEntry()).toBeNull();
  });

  it('참가한 모임 목록에도 채팅 버튼을 반복 표시하지 않는다', async () => {
    const { getMyRooms } = renderMyRooms(myRoom({ myRole: 'JOINED', participationStatus: 'ACTIVE', status: 'CLOSED', chatAvailable: true }));

    await waitFor(() => expect(screen.getByText(/홍대 보드게임 모임/)).toBeTruthy());
    expect(getMyRooms).toHaveBeenCalledWith(expect.objectContaining({ role: 'joined' }), expect.anything());
    expect(screen.queryByText(/다른 탭 모임/)).toBeNull();
    expect(chatEntry()).toBeNull();
  });
});

describe('#427 T3 참가 취소·최종 상태 방', () => {
  it('취소된 모임에도 채팅 버튼을 표시하지 않는다', async () => {
    renderMyRooms(myRoom({ status: 'CANCELED', chatAvailable: false }));

    await waitFor(() => expect(screen.getByText('취소됨')).toBeTruthy());
    expect(chatEntry()).toBeNull();
  });

  it('채팅 가능 여부 필드가 없는 모임에도 채팅 버튼을 표시하지 않는다', async () => {
    const room = myRoom({ status: 'FINISHED' });
    delete room.chatAvailable;
    renderMyRooms(room);

    await waitFor(() => expect(screen.getByText('종료됨')).toBeTruthy());
    expect(chatEntry()).toBeNull();
  });
});

describe('#427 T4 직접 URL 접근 거절', () => {
  it('서버가 FORBIDDEN으로 거절하면 안내만 남기고 메시지를 보여주지 않는다', async () => {
    vi.spyOn(api, 'getChatMessages').mockRejectedValue(new ApiError({
      status: 403,
      code: 'FORBIDDEN',
      message: '주최자 또는 현재 참가자만 접근할 수 있습니다.'
    }));

    render(<ChatRoomView roomId="7" dataVersion={0} />);

    await waitFor(() => expect(screen.getByText(/이 모임의 채팅에 들어갈 수 없어요/)).toBeTruthy());
    expect(screen.queryByRole('list')).toBeNull();
  });

  it('없는 방이면 방을 찾을 수 없다는 상태로 전환한다', async () => {
    vi.spyOn(api, 'getChatMessages').mockRejectedValue(new ApiError({
      status: 404,
      code: 'ROOM_NOT_FOUND',
      message: '방을 찾을 수 없습니다.'
    }));

    render(<ChatRoomView roomId="999" dataVersion={0} />);

    await waitFor(() => expect(screen.getByText('모임을 찾을 수 없어 채팅을 열 수 없어요.')).toBeTruthy());
  });
});

// 채팅 라우트는 App이 배선하므로 해시 진입 경로는 App을 렌더해 확인한다.
function stubAppDependencies(profile) {
  vi.spyOn(api, 'getMyProfile').mockImplementation(() => (profile
    ? Promise.resolve(profile)
    : Promise.reject(new ApiError({ status: 401, code: 'UNAUTHENTICATED', message: '로그인이 필요합니다.' }))));
  vi.spyOn(api, 'getSocialProviders').mockResolvedValue([]);
  vi.spyOn(api, 'getNotifications').mockResolvedValue({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 });
  vi.spyOn(api, 'getUnreadNotificationCount').mockResolvedValue({ unreadCount: 0 });
}

describe('#427 T4 채팅 라우트 진입', () => {
  it('로그인 상태의 #/chat/{roomId} 진입은 해당 방의 채팅 화면을 연다', async () => {
    stubAppDependencies({ id: 1, nickname: '테스터' });
    const getChatMessages = vi.spyOn(api, 'getChatMessages').mockResolvedValue({
      messages: [{ messageId: 1, roomId: 7, clientMessageId: 'c1', sender: { nickname: '주최자' }, isMine: false, content: '7시에 만나요', createdAt: '2026-09-01T19:00:00+09:00' }],
      nextBeforeMessageId: null,
      hasNext: false
    });
    window.location.hash = '#/chat/7';

    render(<App />);

    await waitFor(() => expect(screen.getByText('7시에 만나요')).toBeTruthy());
    expect(getChatMessages).toHaveBeenCalledWith('7', expect.anything());
    expect(screen.queryByRole('navigation', { name: '모바일 주요 메뉴' })).toBeNull();
  });

  it('로그인하지 않은 채로 채팅 주소에 들어오면 이력을 요청하지 않고 로그인을 안내한다', async () => {
    stubAppDependencies(null);
    const getChatMessages = vi.spyOn(api, 'getChatMessages');
    window.location.hash = '#/chat/7';

    render(<App />);

    await waitFor(() => expect(screen.getByText('모임 채팅을 보려면 로그인해주세요.')).toBeTruthy());
    expect(getChatMessages).not.toHaveBeenCalled();
  });
});

describe('#427 T5 채팅 화면 이력 표시', () => {
  it('모바일 채팅 헤더에 전체 채팅과 모임 상세 동선을 제공한다', async () => {
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({
      messages: [{ messageId: 1, roomId: 7, clientMessageId: 'c1', sender: { nickname: '주최자' }, isMine: false, content: '7시에 만나요', createdAt: '2026-09-01T19:00:00+09:00' }],
      nextBeforeMessageId: null,
      hasNext: false
    });
    vi.spyOn(api, 'getRoom').mockResolvedValue(myRoom({ participantCount: 3 }));

    const { container } = render(<ChatRoomView roomId="7" dataVersion={0} />);

    await waitFor(() => expect(container.querySelector('.chat-mobile-header')).toBeTruthy());
    expect(container.querySelector('.chat-mobile-title')?.textContent).toBe('홍대 보드게임 모임');
    expect(screen.getByRole('link', { name: '전체 채팅으로 돌아가기' }).getAttribute('href')).toBe('#/chats');
    expect(screen.getByRole('link', { name: '모임 상세 보기' }).getAttribute('href')).toBe('#/session/7');
  });

  it('허용된 방의 이력을 오래된 순서로 일반 텍스트로 보여준다', async () => {
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({
      messages: [
        { messageId: 2, roomId: 7, clientMessageId: 'c2', sender: { nickname: '참가자' }, isMine: false, content: '<img src=x onerror=alert(1)>', createdAt: '2026-09-01T19:05:00+09:00' },
        { messageId: 1, roomId: 7, clientMessageId: 'c1', sender: { nickname: '주최자' }, isMine: false, content: '7시에 만나요', createdAt: '2026-09-01T19:00:00+09:00' }
      ],
      nextBeforeMessageId: null,
      hasNext: false
    });

    const { container } = render(<ChatRoomView roomId="7" dataVersion={0} />);

    await waitFor(() => expect(screen.getByText('7시에 만나요')).toBeTruthy());
    const rendered = Array.from(container.querySelectorAll('.chat-message b')).map((node) => node.textContent);
    expect(rendered).toEqual(['주최자', '참가자']);
    expect(container.querySelector('.chat-log img')).toBeNull();
  });

  it('내 메시지는 오른쪽 나 말풍선, 상대 메시지는 왼쪽 상대 말풍선으로 구분한다', async () => {
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({
      messages: [
        { messageId: 2, roomId: 7, sender: { nickname: '테스터' }, isMine: false, content: '동명이인 상대', createdAt: '2026-09-01T19:05:00+09:00' },
        { messageId: 1, roomId: 7, sender: { nickname: '테스터' }, isMine: true, content: '내 메시지', createdAt: '2026-09-01T19:00:00+09:00' }
      ],
      nextBeforeMessageId: null,
      hasNext: false
    });
    const { container } = render(<ChatRoomView roomId="7" dataVersion={1} me={{ id: 1, nickname: '테스터' }} />);

    await waitFor(() => expect(container.querySelector('[data-message-owner="mine"]')).toBeTruthy());
    expect(container.querySelectorAll('[data-message-owner="mine"]')).toHaveLength(1);
    expect(container.querySelectorAll('[data-message-owner="theirs"]')).toHaveLength(1);
    expect(screen.getByText('나')).toBeTruthy();
    expect(screen.getByText('동명이인 상대')).toBeTruthy();
  });
});

describe('#431 CHAT-03 실시간 수신·재연결', () => {
  function useFakeWebSocket() {
    FakeWebSocket.instances = [];
    vi.stubGlobal('WebSocket', FakeWebSocket);
    return FakeWebSocket.instances;
  }

  it('채팅 화면이 확정 이벤트를 실시간으로 한 번 표시한다', async () => {
    const sockets = useFakeWebSocket();
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({
      messages: [{ messageId: 10, roomId: 7, sender: { nickname: '주최자' }, isMine: false, content: '기존 메시지', createdAt: '2026-09-01T19:00:00+09:00' }],
      nextBeforeMessageId: null,
      hasNext: false
    });

    render(<ChatRoomView roomId="7" dataVersion={0} />);
    await waitFor(() => expect(sockets).toHaveLength(1));
    expect(sockets[0].url).toContain('/api/rooms/7/chat/ws?afterMessageId=10');
    sockets[0].open();
    sockets[0].message({
      eventId: 11,
      type: 'MESSAGE_CREATED',
      message: { messageId: 11, roomId: 7, sender: { nickname: '참가자' }, isMine: false, content: '방금 도착한 메시지', createdAt: '2026-09-01T19:01:00+09:00' }
    });

    await waitFor(() => expect(screen.getByText('방금 도착한 메시지')).toBeTruthy());
    expect(screen.queryByText('실시간 연결됨')).toBeNull();
    expect(screen.queryByText(/실시간 연결.*중/)).toBeNull();
    sockets[0].message({
      eventId: 99,
      type: 'MESSAGE_CREATED',
      message: { messageId: 13, roomId: 7, sender: { nickname: '참가자' }, isMine: false, content: '잘못된 식별자', createdAt: '2026-09-01T19:02:00+09:00' }
    });
    expect(screen.queryByText('잘못된 식별자')).toBeNull();
  });

  it('HTTP 저장 응답과 같은 WebSocket 이벤트는 메시지 하나로 합친다', async () => {
    const sockets = useFakeWebSocket();
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({ messages: [], nextBeforeMessageId: null, hasNext: false });
    vi.spyOn(api, 'sendChatMessage').mockResolvedValue({
      messageId: 12,
      roomId: 7,
      sender: { nickname: '테스터' },
      isMine: true,
      content: '중복 없는 메시지',
      createdAt: '2026-09-01T19:02:00+09:00'
    });

    render(<ChatRoomView roomId="7" dataVersion={0} />);
    await waitFor(() => expect(sockets).toHaveLength(1));
    sockets[0].open();
    fireEvent.change(await screen.findByLabelText('메시지'), { target: { value: '중복 없는 메시지' } });
    fireEvent.click(screen.getByRole('button', { name: '전송' }));
    await waitFor(() => expect(screen.getByText('중복 없는 메시지')).toBeTruthy());
    sockets[0].message({
      eventId: 12,
      type: 'MESSAGE_CREATED',
      message: { messageId: 12, roomId: 7, sender: { nickname: '테스터' }, isMine: true, content: '중복 없는 메시지', createdAt: '2026-09-01T19:02:00+09:00' }
    });

    await waitFor(() => expect(screen.getAllByText('중복 없는 메시지')).toHaveLength(1));
  });

  it('Enter로 전송하고 Shift+Enter의 LF payload를 그대로 전송한다', async () => {
    const sockets = useFakeWebSocket();
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({ messages: [], nextBeforeMessageId: null, hasNext: false });
    const send = vi.spyOn(api, 'sendChatMessage').mockResolvedValue({
      messageId: 13,
      roomId: 7,
      sender: { nickname: '테스터' },
      isMine: true,
      content: '첫 줄\n둘째 줄',
      createdAt: '2026-09-01T19:02:00+09:00'
    });

    render(<ChatRoomView roomId="7" dataVersion={0} />);
    await waitFor(() => expect(sockets).toHaveLength(1));
    const input = await screen.findByLabelText('메시지');
    fireEvent.change(input, { target: { value: '첫 줄\n둘째 줄' } });
    const shiftEnter = new KeyboardEvent('keydown', { key: 'Enter', shiftKey: true, bubbles: true, cancelable: true });
    fireEvent(input, shiftEnter);
    expect(shiftEnter.defaultPrevented).toBe(false);
    expect(send).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '전송' }));
    await waitFor(() => expect(send).toHaveBeenCalledWith(
      '7',
      expect.objectContaining({ content: '첫 줄\n둘째 줄' }),
      expect.any(AbortSignal),
      expect.any(Function)
    ));
    fireEvent.change(input, { target: { value: '키보드 전송' } });
    fireEvent.keyDown(input, { key: 'Enter' });

    await waitFor(() => expect(send).toHaveBeenCalledWith(
      '7',
      expect.objectContaining({ content: '키보드 전송' }),
      expect.any(AbortSignal),
      expect.any(Function)
    ));
  });

  it('전송을 마치면 입력에 포커스를 되돌려 이어서 칠 수 있다', async () => {
    useFakeWebSocket();
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({ messages: [], nextBeforeMessageId: null, hasNext: false });
    vi.spyOn(api, 'sendChatMessage').mockResolvedValue({
      messageId: 1,
      roomId: 7,
      sender: { nickname: '테스터' },
      isMine: true,
      content: '이어서 칠게요',
      createdAt: '2026-09-01T19:01:00+09:00'
    });

    render(<ChatRoomView roomId="7" dataVersion={0} />);
    await waitFor(() => expect(screen.getByLabelText('메시지')).toBeTruthy());

    const input = screen.getByLabelText('메시지');
    input.focus();
    fireEvent.change(input, { target: { value: '이어서 칠게요' } });
    fireEvent.click(screen.getByRole('button', { name: '전송' }));
    // 실제 브라우저는 disabled가 되는 순간 포커스를 뗀다. jsdom은 disabled 요소의 blur를 무시하므로
    // 다른 요소로 포커스를 옮겨 같은 상태를 만든다.
    const elsewhere = document.body.appendChild(document.createElement('input'));
    elsewhere.focus();
    expect(document.activeElement).toBe(elsewhere);

    await waitFor(() => expect(input.disabled).toBe(false));
    await waitFor(() => expect(document.activeElement).toBe(input));
    elsewhere.remove();
  });

  it('전송에 실패해도 입력에 포커스를 되돌려 고쳐서 다시 보낼 수 있다', async () => {
    useFakeWebSocket();
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({ messages: [], nextBeforeMessageId: null, hasNext: false });
    vi.spyOn(api, 'sendChatMessage').mockRejectedValue(new ApiError({
      status: 503,
      code: 'SERVICE_UNAVAILABLE',
      message: '일시적으로 메시지를 보낼 수 없습니다.'
    }));

    render(<ChatRoomView roomId="7" dataVersion={0} />);
    await waitFor(() => expect(screen.getByLabelText('메시지')).toBeTruthy());

    const input = screen.getByLabelText('메시지');
    input.focus();
    fireEvent.change(input, { target: { value: '실패할 메시지' } });
    fireEvent.click(screen.getByRole('button', { name: '전송' }));
    // 실제 브라우저는 disabled가 되는 순간 포커스를 뗀다. jsdom은 disabled 요소의 blur를 무시하므로
    // 다른 요소로 포커스를 옮겨 같은 상태를 만든다.
    const elsewhere = document.body.appendChild(document.createElement('input'));
    elsewhere.focus();
    expect(document.activeElement).toBe(elsewhere);

    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(input.disabled).toBe(false);
    await waitFor(() => expect(document.activeElement).toBe(input));
    elsewhere.remove();
  });

  it('CSRF 대기 중에는 deadline을 시작하지 않고 실제 POST 시작 뒤에만 결과 미확정으로 처리한다', async () => {
    useFakeWebSocket();
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({ messages: [], nextBeforeMessageId: null, hasNext: false });
    let startPost;
    vi.spyOn(api, 'sendChatMessage').mockImplementation((_roomId, _message, signal, onRequestStarted) => new Promise((_resolve, reject) => {
      signal.addEventListener('abort', () => reject(signal.reason), { once: true });
      startPost = () => onRequestStarted();
    }));

    render(<ChatRoomView roomId="7" dataVersion={0} />);
    await waitFor(() => expect(screen.getByLabelText('메시지')).toBeTruthy());
    vi.useFakeTimers();
    fireEvent.change(screen.getByLabelText('메시지'), { target: { value: 'CSRF 대기 메시지' } });
    fireEvent.click(screen.getByRole('button', { name: '전송' }));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(CHAT_SEND_REQUEST_DEADLINE_MS);
    });

    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByRole('button', { name: '전송 중…' })).toBeTruthy();

    startPost();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(CHAT_SEND_REQUEST_DEADLINE_MS);
    });

    expect(screen.getByRole('alert').textContent).toContain(CHAT_SEND_RESULT_UNKNOWN_MESSAGE);
  });

  it('deadline 뒤 HTTP 503 ApiError가 도착해도 결과 미확정 대신 서버 오류를 보여준다', async () => {
    useFakeWebSocket();
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({ messages: [], nextBeforeMessageId: null, hasNext: false });
    let rejectSend;
    vi.spyOn(api, 'sendChatMessage').mockImplementation((_roomId, _message, _signal, onRequestStarted) => new Promise((_resolve, reject) => {
      onRequestStarted();
      rejectSend = reject;
    }));

    render(<ChatRoomView roomId="7" dataVersion={0} />);
    await waitFor(() => expect(screen.getByLabelText('메시지')).toBeTruthy());
    vi.useFakeTimers();
    fireEvent.change(screen.getByLabelText('메시지'), { target: { value: '503 응답 메시지' } });
    fireEvent.click(screen.getByRole('button', { name: '전송' }));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(CHAT_SEND_REQUEST_DEADLINE_MS);
      rejectSend(new ApiError({ status: 503, code: 'SERVICE_UNAVAILABLE', message: '채팅 서버가 일시적으로 응답하지 않아요.' }));
      await Promise.resolve();
    });

    expect(screen.getByRole('alert').textContent).toContain('채팅 서버가 일시적으로 응답하지 않아요.');
    expect(screen.queryByText(CHAT_SEND_RESULT_UNKNOWN_MESSAGE)).toBeNull();
    expect(screen.getByRole('button', { name: '전송' })).toBeTruthy();
  });

  it('T3 deadline 뒤 결과 미확정 문구와 다시 시도 상태를 보여주고 같은 clientMessageId를 유지한다', async () => {
    useFakeWebSocket();
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({ messages: [], nextBeforeMessageId: null, hasNext: false });
    let attempt = 0;
    const send = vi.spyOn(api, 'sendChatMessage').mockImplementation((_roomId, _message, signal, onRequestStarted) => {
      attempt += 1;
      if (attempt === 1) {
        onRequestStarted();
        return new Promise((_resolve, reject) => {
          signal.addEventListener('abort', () => reject(signal.reason));
        });
      }
      return Promise.resolve({
        messageId: 15,
        roomId: 7,
        sender: { nickname: '테스터' },
        isMine: true,
        content: '응답이 늦은 메시지',
        createdAt: '2026-09-01T19:15:00+09:00'
      });
    });

    render(<ChatRoomView roomId="7" dataVersion={0} />);
    await waitFor(() => expect(screen.getByLabelText('메시지')).toBeTruthy());
    vi.useFakeTimers();
    const input = screen.getByLabelText('메시지');
    fireEvent.change(input, { target: { value: '응답이 늦은 메시지' } });
    fireEvent.click(screen.getByRole('button', { name: '전송' }));
    const firstMessageId = send.mock.calls[0][1].clientMessageId;

    await act(async () => {
      await vi.advanceTimersByTimeAsync(CHAT_SEND_REQUEST_DEADLINE_MS);
    });

    expect(screen.getByRole('alert').textContent).toContain(CHAT_SEND_RESULT_UNKNOWN_MESSAGE);
    expect(input.disabled).toBe(false);
    expect(input.value).toBe('응답이 늦은 메시지');
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await act(async () => { await Promise.resolve(); });
    expect(screen.getByText('응답이 늦은 메시지')).toBeTruthy();
    expect(send.mock.calls[1][1].clientMessageId).toBe(firstMessageId);
  });

  it('전송 계층 오류도 결과 미확정 문구와 같은 clientMessageId 수동 재시도를 제공한다', async () => {
    useFakeWebSocket();
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({ messages: [], nextBeforeMessageId: null, hasNext: false });
    let attempt = 0;
    const send = vi.spyOn(api, 'sendChatMessage').mockImplementation((_roomId, _message, _signal, onRequestStarted) => {
      attempt += 1;
      onRequestStarted();
      if (attempt === 1) return Promise.reject(new TypeError('network failure'));
      return Promise.resolve({
        messageId: 16,
        roomId: 7,
        sender: { nickname: '테스터' },
        isMine: true,
        content: '네트워크 재시도 메시지',
        createdAt: '2026-09-01T19:16:00+09:00'
      });
    });

    render(<ChatRoomView roomId="7" dataVersion={0} />);
    await waitFor(() => expect(screen.getByLabelText('메시지')).toBeTruthy());
    const input = screen.getByLabelText('메시지');
    fireEvent.change(input, { target: { value: '네트워크 재시도 메시지' } });
    fireEvent.click(screen.getByRole('button', { name: '전송' }));
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain(CHAT_SEND_RESULT_UNKNOWN_MESSAGE));

    const firstMessageId = send.mock.calls[0][1].clientMessageId;
    expect(input.value).toBe('네트워크 재시도 메시지');
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(screen.getByText('네트워크 재시도 메시지')).toBeTruthy());
    expect(send.mock.calls[1][1].clientMessageId).toBe(firstMessageId);
  });

  it('메시지를 보내면 채팅 목록을 하단으로 이동한다', async () => {
    const sockets = useFakeWebSocket();
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({
      messages: [{ messageId: 1, roomId: 7, sender: { nickname: '상대' }, isMine: false, content: '기존 메시지', createdAt: '2026-09-01T19:00:00+09:00' }],
      nextBeforeMessageId: null,
      hasNext: false
    });
    vi.spyOn(api, 'sendChatMessage').mockResolvedValue({
      messageId: 2,
      roomId: 7,
      sender: { nickname: '테스터' },
      isMine: true,
      content: '아래로 이동',
      createdAt: '2026-09-01T19:01:00+09:00'
    });

    render(<ChatRoomView roomId="7" dataVersion={0} />);
    await waitFor(() => expect(sockets).toHaveLength(1));
    const history = document.querySelector('.chat-log');
    Object.defineProperties(history, {
      scrollHeight: { configurable: true, value: 100 },
      clientHeight: { configurable: true, value: 50 },
      scrollTop: { configurable: true, writable: true, value: 0 }
    });
    fireEvent.scroll(history);
    fireEvent.change(screen.getByLabelText('메시지'), { target: { value: '아래로 이동' } });
    fireEvent.click(screen.getByRole('button', { name: '전송' }));

    await waitFor(() => expect(screen.getByText('아래로 이동')).toBeTruthy());
    expect(history.scrollTop).toBe(100);
  });

  it('보낸 메시지의 실시간 이벤트가 HTTP 저장 응답보다 먼저 와도 즉시 하단으로 이동한다', async () => {
    const sockets = useFakeWebSocket();
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({
      messages: [{ messageId: 1, roomId: 7, sender: { nickname: '상대' }, isMine: false, content: '기존 메시지', createdAt: '2026-09-01T19:00:00+09:00' }],
      nextBeforeMessageId: null,
      hasNext: false
    });
    let resolveSend;
    vi.spyOn(api, 'sendChatMessage').mockImplementation(() => new Promise((resolve) => { resolveSend = resolve; }));

    render(<ChatRoomView roomId="7" dataVersion={0} />);
    await waitFor(() => expect(sockets).toHaveLength(1));
    const history = document.querySelector('.chat-log');
    let currentScrollTop = 0;
    Object.defineProperties(history, {
      scrollHeight: { configurable: true, value: 100 },
      clientHeight: { configurable: true, value: 50 },
      scrollTop: {
        configurable: true,
        get: () => currentScrollTop,
        set: (value) => { currentScrollTop = value; }
      }
    });
    // 사용자가 위로 스크롤해 하단에 있지 않은 상태를 만든다.
    currentScrollTop = 0;
    fireEvent.scroll(history);
    fireEvent.change(screen.getByLabelText('메시지'), { target: { value: '경합 메시지' } });
    fireEvent.click(screen.getByRole('button', { name: '전송' }));

    // HTTP 응답이 아직 오지 않은 상태에서 같은 메시지의 실시간 이벤트가 먼저 도착한다.
    await act(async () => {
      sockets[0].message({
        eventId: 2,
        type: 'MESSAGE_CREATED',
        message: { messageId: 2, roomId: 7, sender: { nickname: '테스터' }, isMine: true, content: '경합 메시지', createdAt: '2026-09-01T19:01:00+09:00' }
      });
      await Promise.resolve();
    });
    // 실시간 이벤트 도착 즉시 하단으로 이동해야 한다. HTTP 응답을 기다리는 다음 메시지까지 지연되지 않는다.
    const chatLog = document.querySelector('.chat-log');
    await waitFor(() => expect(within(chatLog).getByText('경합 메시지')).toBeTruthy());
    expect(history.scrollTop).toBe(100);

    // 같은 messageId의 HTTP 응답은 중복 병합이라 목록 길이가 바뀌지 않는다. 별도 스크롤 없이 안전하게 끝난다.
    currentScrollTop = 42;
    await act(async () => {
      resolveSend({ messageId: 2, roomId: 7, sender: { nickname: '테스터' }, isMine: true, content: '경합 메시지', createdAt: '2026-09-01T19:01:00+09:00' });
      await Promise.resolve();
    });
    await waitFor(() => expect(screen.getByLabelText('메시지').disabled).toBe(false));
    expect(history.scrollTop).toBe(42);
  });

  it('연결이 끊기면 마지막 이벤트 ID로 재연결한다', async () => {
    vi.useFakeTimers();
    const sockets = useFakeWebSocket();
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({
      messages: [{ messageId: 20, roomId: 7, sender: { nickname: '상대' }, isMine: false, content: '마지막 이력', createdAt: '2026-09-01T19:00:00+09:00' }],
      nextBeforeMessageId: null,
      hasNext: false
    });

    render(<ChatRoomView roomId="7" dataVersion={0} />);
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(sockets).toHaveLength(1);
    sockets[0].open();
    await act(async () => {
      sockets[0].drop();
    });
    expect(screen.getByRole('status').textContent).toContain('실시간 채팅을 다시 연결하고 있어요.');
    await act(async () => {
      vi.advanceTimersByTime(500);
      await Promise.resolve();
    });

    expect(sockets).toHaveLength(2);
    expect(sockets[1].url).toContain('/api/rooms/7/chat/ws?afterMessageId=20');
    sockets[1].open();
    await act(async () => {
      sockets[1].message({
        eventId: 21,
        type: 'MESSAGE_CREATED',
        message: { messageId: 21, roomId: 7, sender: { nickname: '상대' }, isMine: false, content: '복구 첫 메시지', createdAt: '2026-09-01T19:01:00+09:00' }
      });
      sockets[1].message({
        eventId: 22,
        type: 'MESSAGE_CREATED',
        message: { messageId: 22, roomId: 7, sender: { nickname: '상대' }, isMine: false, content: '복구 다음 메시지', createdAt: '2026-09-01T19:02:00+09:00' }
      });
    });
    expect(Array.from(document.querySelectorAll('.chat-content')).map((node) => node.textContent)).toEqual(['마지막 이력', '복구 첫 메시지', '복구 다음 메시지']);
    vi.useRealTimers();
  });

  it('파싱할 수 없는 프레임 뒤 마지막 정상 이벤트 ID부터 다시 연결한다', async () => {
    vi.useFakeTimers();
    const sockets = useFakeWebSocket();
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({
      messages: [{ messageId: 20, roomId: 7, sender: { nickname: '상대' }, isMine: false, content: '마지막 이력', createdAt: '2026-09-01T19:00:00+09:00' }],
      nextBeforeMessageId: null,
      hasNext: false
    });

    render(<ChatRoomView roomId="7" dataVersion={0} />);
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(sockets).toHaveLength(1);
    sockets[0].open();

    await act(async () => {
      sockets[0].rawMessage('{malformed');
      sockets[0].message({
        eventId: 22,
        type: 'MESSAGE_CREATED',
        message: { messageId: 22, roomId: 7, sender: { nickname: '상대' }, isMine: false, content: '기존 연결의 후속 메시지', createdAt: '2026-09-01T19:02:00+09:00' }
      });
    });

    expect(screen.queryByText('기존 연결의 후속 메시지')).toBeNull();
    expect(screen.getByRole('status').textContent).toContain('실시간 채팅을 다시 연결하고 있어요.');
    await act(async () => {
      vi.advanceTimersByTime(500);
      await Promise.resolve();
    });

    expect(sockets).toHaveLength(2);
    expect(sockets[1].url).toContain('/api/rooms/7/chat/ws?afterMessageId=20');
    await act(async () => {
      sockets[1].message({
        eventId: 21,
        type: 'MESSAGE_CREATED',
        message: { messageId: 21, roomId: 7, sender: { nickname: '상대' }, isMine: false, content: '복구된 누락 메시지', createdAt: '2026-09-01T19:01:00+09:00' }
      });
      sockets[1].message({
        eventId: 22,
        type: 'MESSAGE_CREATED',
        message: { messageId: 22, roomId: 7, sender: { nickname: '상대' }, isMine: false, content: '복구된 후속 메시지', createdAt: '2026-09-01T19:02:00+09:00' }
      });
    });

    expect(Array.from(document.querySelectorAll('.chat-content')).map((node) => node.textContent)).toEqual([
      '마지막 이력',
      '복구된 누락 메시지',
      '복구된 후속 메시지'
    ]);
  });

  it('정책 위반 종료는 재연결하지 않고 재진입 동선을 제공한다', async () => {
    vi.useFakeTimers();
    const sockets = useFakeWebSocket();
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({
      messages: [{ messageId: 30, roomId: 7, sender: { nickname: '상대' }, isMine: false, content: '기존 메시지', createdAt: '2026-09-01T19:00:00+09:00' }],
      nextBeforeMessageId: null,
      hasNext: false
    });

    render(<ChatRoomView roomId="7" dataVersion={0} />);
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(sockets).toHaveLength(1);
    sockets[0].open();
    sockets[0].drop(1008);
    await act(async () => {
      vi.advanceTimersByTime(8000);
      await Promise.resolve();
    });

    expect(sockets).toHaveLength(1);
    expect(screen.getByRole('status').textContent).toContain('이 채팅의 실시간 연결이 종료됐어요.');
    expect(screen.getByRole('link', { name: '채팅 목록으로 이동' }).getAttribute('href')).toBe('#/chats');
    vi.useRealTimers();
  });

  it('초기 실시간 연결 실패에는 상태와 채팅 목록 재진입 동선을 제공한다', async () => {
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({
      messages: [],
      nextBeforeMessageId: null,
      hasNext: false
    });
    vi.spyOn(api, 'openChatWebSocket').mockImplementation(() => { throw new Error('연결 실패'); });

    render(<ChatRoomView roomId="7" dataVersion={0} />);

    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('실시간 채팅 연결을 시작하지 못했어요.'));
    expect(screen.getByRole('link', { name: '채팅 목록으로 이동' }).getAttribute('href')).toBe('#/chats');
  });

  it('재연결 한도를 모두 쓰면 종료 상태와 채팅 목록 재진입 동선을 제공한다', async () => {
    vi.useFakeTimers();
    const sockets = useFakeWebSocket();
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({
      messages: [],
      nextBeforeMessageId: null,
      hasNext: false
    });

    render(<ChatRoomView roomId="7" dataVersion={0} />);
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    sockets[0].open();
    for (const delay of [500, 1000, 2000, 4000, 8000]) {
      sockets.at(-1).drop();
      await act(async () => {
        vi.advanceTimersByTime(delay);
        await Promise.resolve();
      });
    }
    await act(async () => {
      sockets.at(-1).drop();
    });

    expect(sockets).toHaveLength(6);
    expect(screen.getByRole('status').textContent).toContain('실시간 채팅을 다시 연결하지 못했어요.');
    expect(screen.getByRole('link', { name: '채팅 목록으로 이동' }).getAttribute('href')).toBe('#/chats');
    vi.useRealTimers();
  });
});

describe('#427 T1~T4 메시지 전송·이력 추가 조회', () => {
  it('유효한 메시지를 전송하고 서버가 확정한 메시지를 목록에 표시한다', async () => {
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({ messages: [], nextBeforeMessageId: null, hasNext: false });
    vi.spyOn(api, 'sendChatMessage').mockResolvedValue({
      messageId: 3,
      roomId: 7,
      sender: { nickname: '테스터' },
      isMine: true,
      content: '저도 참여할게요',
      createdAt: '2026-09-01T19:10:00+09:00'
    });

    render(<ChatRoomView roomId="7" dataVersion={0} />);
    await waitFor(() => expect(screen.getByLabelText('메시지')).toBeTruthy());
    const input = screen.getByLabelText('메시지');
    fireEvent.change(input, { target: { value: '저도 참여할게요' } });
    fireEvent.click(screen.getByRole('button', { name: '전송' }));

    await waitFor(() => expect(screen.getByText('저도 참여할게요')).toBeTruthy());
    expect(api.sendChatMessage).toHaveBeenCalledWith(
      '7',
      expect.objectContaining({ content: '저도 참여할게요', clientMessageId: expect.any(String) }),
      expect.any(AbortSignal),
      expect.any(Function)
    );
  });

  it('전송 실패 뒤 같은 본문은 같은 키로 재시도하고 본문을 바꾸면 새 키를 발급한다', async () => {
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({ messages: [], nextBeforeMessageId: null, hasNext: false });
    const send = vi.spyOn(api, 'sendChatMessage')
      .mockRejectedValueOnce(new ApiError({ status: 503, code: 'SERVICE_UNAVAILABLE', message: '잠시 후 다시 시도해주세요.' }))
      .mockRejectedValueOnce(new ApiError({ status: 503, code: 'SERVICE_UNAVAILABLE', message: '잠시 후 다시 시도해주세요.' }))
      .mockResolvedValueOnce({
        messageId: 4,
        roomId: 7,
        sender: { nickname: '테스터' },
        isMine: true,
        content: '수정한 메시지',
        createdAt: '2026-09-01T19:11:00+09:00'
      });

    render(<ChatRoomView roomId="7" dataVersion={0} />);
    await waitFor(() => expect(screen.getByLabelText('메시지')).toBeTruthy());
    const input = screen.getByLabelText('메시지');
    const sendButton = screen.getByRole('button', { name: '전송' });
    fireEvent.change(input, { target: { value: '첫 번째 메시지' } });
    fireEvent.click(sendButton);
    await waitFor(() => expect(screen.getByText('잠시 후 다시 시도해주세요.')).toBeTruthy());
    const firstMessageId = send.mock.calls[0][1].clientMessageId;

    fireEvent.click(sendButton);
    await waitFor(() => expect(send).toHaveBeenCalledTimes(2));
    expect(send.mock.calls[1][1].clientMessageId).toBe(firstMessageId);

    fireEvent.change(input, { target: { value: '수정한 메시지' } });
    fireEvent.click(sendButton);
    await waitFor(() => expect(screen.getByText('수정한 메시지')).toBeTruthy());
    expect(send.mock.calls[2][1].clientMessageId).not.toBe(firstMessageId);
  });

  it('느린 초기 이력 응답이 전송 성공 메시지를 덮어쓰지 않는다', async () => {
    let resolveHistory;
    vi.spyOn(api, 'getChatMessages').mockReturnValue(new Promise((resolve) => { resolveHistory = resolve; }));
    vi.spyOn(api, 'sendChatMessage').mockResolvedValue({
      messageId: 3,
      roomId: 7,
      sender: { nickname: '테스터' },
      isMine: true,
      content: '먼저 보낸 메시지',
      createdAt: '2026-09-01T19:10:00+09:00'
    });

    render(<ChatRoomView roomId="7" dataVersion={0} />);
    fireEvent.change(screen.getByLabelText('메시지'), { target: { value: '먼저 보낸 메시지' } });
    fireEvent.click(screen.getByRole('button', { name: '전송' }));
    await waitFor(() => expect(screen.getByText('먼저 보낸 메시지')).toBeTruthy());

    resolveHistory({ messages: [], nextBeforeMessageId: null, hasNext: false });
    await waitFor(() => expect(screen.getByText('먼저 보낸 메시지')).toBeTruthy());
  });

  it('방을 바꾸면 이전 방에서 진행 중인 전송 결과를 새 방에 표시하지 않는다', async () => {
    let resolveSend;
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({ messages: [], nextBeforeMessageId: null, hasNext: false });
    vi.spyOn(api, 'sendChatMessage').mockReturnValue(new Promise((resolve) => { resolveSend = resolve; }));
    const view = render(<ChatRoomView roomId="7" dataVersion={0} />);
    await waitFor(() => expect(screen.getByLabelText('메시지')).toBeTruthy());
    fireEvent.change(screen.getByLabelText('메시지'), { target: { value: '이전 방 메시지' } });
    fireEvent.click(screen.getByRole('button', { name: '전송' }));

    view.rerender(<ChatRoomView roomId="8" dataVersion={0} />);
    resolveSend({ messageId: 9, roomId: 7, sender: { nickname: '테스터' }, content: '이전 방 메시지', createdAt: '2026-09-01T19:10:00+09:00' });

    await waitFor(() => expect(screen.getByLabelText('메시지').disabled).toBe(false));
    expect(screen.queryByText('이전 방 메시지')).toBeNull();
  });

  it('방을 바꾸면 이전 방에서 진행 중인 과거 이력 조회와 커서를 버린다', async () => {
    let resolvePrevious;
    const get = vi.spyOn(api, 'getChatMessages').mockImplementation((roomId, optionsOrSignal) => {
      if (roomId === '7' && optionsOrSignal?.beforeMessageId) {
        return new Promise((resolve) => { resolvePrevious = resolve; });
      }
      return Promise.resolve(roomId === '7'
        ? { messages: [{ messageId: 3, sender: { nickname: '나' }, content: '현재 방', createdAt: '2026-09-01T19:02:00+09:00' }], nextBeforeMessageId: 3, hasNext: true }
        : { messages: [{ messageId: 8, sender: { nickname: '새 방' }, content: '새 방 현재 이력', createdAt: '2026-09-01T19:03:00+09:00' }], nextBeforeMessageId: 8, hasNext: true });
    });
    FakeIntersectionObserver.instances = [];
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver);
    const view = render(<ChatRoomView roomId="7" dataVersion={0} />);
    await waitFor(() => expect(FakeIntersectionObserver.instances).toHaveLength(1));
    FakeIntersectionObserver.instances[0].trigger();

    view.rerender(<ChatRoomView roomId="8" dataVersion={0} />);
    await waitFor(() => expect(screen.getByText('새 방 현재 이력')).toBeTruthy());
    await act(async () => {
      resolvePrevious({ messages: [{ messageId: 2, sender: { nickname: '이전' }, content: '이전 방 이력', createdAt: '2026-09-01T19:01:00+09:00' }], nextBeforeMessageId: null, hasNext: false });
      await Promise.resolve();
    });

    await waitFor(() => expect(get).toHaveBeenCalledTimes(3));
    expect(screen.queryByText('이전 방 이력')).toBeNull();
    expect(screen.getByText('새 방 현재 이력')).toBeTruthy();
  });

  it('공백과 500자 초과 입력은 전송하지 않고 화면에서 거절한다', async () => {
    const send = vi.spyOn(api, 'sendChatMessage').mockResolvedValue({});
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({ messages: [], nextBeforeMessageId: null, hasNext: false });
    render(<ChatRoomView roomId="7" dataVersion={0} />);
    await waitFor(() => expect(screen.getByLabelText('메시지')).toBeTruthy());

    const input = screen.getByLabelText('메시지');
    fireEvent.click(screen.getByRole('button', { name: '전송' }));
    expect(screen.getByText('메시지를 입력해주세요.')).toBeTruthy();
    fireEvent.change(input, { target: { value: 'a'.repeat(501) } });
    fireEvent.click(screen.getByRole('button', { name: '전송' }));
    expect(screen.getByText('메시지는 500자까지 입력할 수 있어요.')).toBeTruthy();
    expect(send).not.toHaveBeenCalled();
  });

  it('과거 이력을 커서로 추가 조회하고 메시지 ID 중복을 제거한다', async () => {
    const get = vi.spyOn(api, 'getChatMessages')
      .mockResolvedValueOnce({ messages: [{ messageId: 3, sender: { nickname: '나' }, content: '세 번째', createdAt: '2026-09-01T19:02:00+09:00' }], nextBeforeMessageId: 3, hasNext: true })
      .mockResolvedValueOnce({ messages: [{ messageId: 3, sender: { nickname: '나' }, content: '세 번째', createdAt: '2026-09-01T19:02:00+09:00' }, { messageId: 2, sender: { nickname: '상대' }, content: '두 번째', createdAt: '2026-09-01T19:01:00+09:00' }], nextBeforeMessageId: null, hasNext: false });
    FakeIntersectionObserver.instances = [];
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver);
    render(<ChatRoomView roomId="7" dataVersion={0} />);
    await waitFor(() => expect(FakeIntersectionObserver.instances).toHaveLength(1));
    FakeIntersectionObserver.instances[0].trigger();

    await waitFor(() => expect(screen.getByText('두 번째')).toBeTruthy());
    expect(get).toHaveBeenNthCalledWith(2, '7', { beforeMessageId: 3, size: 50 });
    expect(screen.getAllByText('세 번째')).toHaveLength(1);
  });

  it('이전 이력 응답보다 실시간 메시지가 먼저 도착해도 읽던 스크롤 위치를 보존한다', async () => {
    FakeWebSocket.instances = [];
    vi.stubGlobal('WebSocket', FakeWebSocket);
    const sockets = FakeWebSocket.instances;
    let resolvePrevious;
    vi.spyOn(api, 'getChatMessages').mockImplementation((roomId, optionsOrSignal) => {
      if (optionsOrSignal?.beforeMessageId) {
        return new Promise((resolve) => { resolvePrevious = resolve; });
      }
      return Promise.resolve({
        messages: [{ messageId: 10, roomId: 7, sender: { nickname: '상대' }, isMine: false, content: '기준 메시지', createdAt: '2026-09-01T19:05:00+09:00' }],
        nextBeforeMessageId: 10,
        hasNext: true
      });
    });
    FakeIntersectionObserver.instances = [];
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver);
    render(<ChatRoomView roomId="7" dataVersion={0} />);
    await waitFor(() => expect(sockets).toHaveLength(1));
    await waitFor(() => expect(screen.getByText('기준 메시지')).toBeTruthy());

    // 사용자가 위로 스크롤해 과거 이력을 보는 상태를 만든다.
    // jsdom은 layout을 계산하지 않으므로 렌더된 메시지 수에 비례하는 높이로 대신한다.
    const history = document.querySelector('.chat-log');
    Object.defineProperties(history, {
      scrollHeight: { configurable: true, get: () => history.querySelectorAll('.chat-message').length * 200 },
      clientHeight: { configurable: true, value: 100 },
      scrollTop: { configurable: true, writable: true, value: 40 }
    });
    fireEvent.scroll(history);

    await waitFor(() => expect(FakeIntersectionObserver.instances.length).toBeGreaterThan(0));
    FakeIntersectionObserver.instances.at(-1).trigger();

    // 이전 이력 응답을 기다리는 동안 실시간 메시지가 먼저 도착한다.
    await act(async () => {
      sockets[0].message({
        eventId: 11,
        type: 'MESSAGE_CREATED',
        message: { messageId: 11, roomId: 7, sender: { nickname: '상대' }, isMine: false, content: '실시간 메시지', createdAt: '2026-09-01T19:06:00+09:00' }
      });
      await Promise.resolve();
    });
    await waitFor(() => expect(screen.getByText('실시간 메시지')).toBeTruthy());

    const scrollTopBeforePrepend = history.scrollTop;

    // 이제 과거 이력이 앞에 붙는다. 늘어난 높이만큼 보정되어야 읽던 위치가 유지된다.
    await act(async () => {
      resolvePrevious({
        messages: [{ messageId: 9, roomId: 7, sender: { nickname: '상대' }, isMine: false, content: '과거 메시지', createdAt: '2026-09-01T19:04:00+09:00' }],
        nextBeforeMessageId: null,
        hasNext: false
      });
      await Promise.resolve();
    });
    await waitFor(() => expect(screen.getByText('과거 메시지')).toBeTruthy());

    expect(history.scrollTop).toBe(scrollTopBeforePrepend + 200);
  });
});

describe('#427 T5~T6 모임 상세 채팅 진입', () => {
  const detailRoom = (roomType, status = 'RECRUITING') => ({
    id: 7,
    title: roomType === 'GAME_FOCUSED' ? '게임 중심 모임' : '사람 중심 모임',
    roomType,
    status,
    startsAt: '2099-09-01T19:00:00+09:00',
    region: '홍대',
    place: '카페',
    experienceLevel: 'ALL_LEVELS',
    isRulemasterLed: false,
    participantCount: 1,
    recruitmentCapacity: 3,
    participants: [],
    myRole: 'HOST',
  });

  it.each(['GAME_FOCUSED', 'PERSON_FOCUSED'])('허용된 %s 상세에 채팅 진입을 표시한다', async (roomType) => {
    vi.spyOn(api, 'getRoom').mockResolvedValue(detailRoom(roomType));
    render(<SessionDetailView sessionId="7" me={{ id: 1, nickname: '테스터' }} onApply={vi.fn()} onCancelApply={vi.fn()} onHostCancel={vi.fn()} onFinish={vi.fn()} dataVersion={0} />);

    await waitFor(() => expect(screen.getByRole('link', { name: '💬 모임 채팅' }).getAttribute('href')).toBe('#/chat/7'));
  });

  it('취소된 모임에서 서버가 거절한 채팅 진입을 표시하지 않는다', async () => {
    vi.spyOn(api, 'getRoom').mockResolvedValue(detailRoom('PERSON_FOCUSED', 'CANCELED'));
    render(<SessionDetailView sessionId="7" me={{ id: 1, nickname: '테스터' }} onApply={vi.fn()} onCancelApply={vi.fn()} onHostCancel={vi.fn()} onFinish={vi.fn()} dataVersion={0} />);

    await waitFor(() => expect(screen.getByText('취소된 모임입니다.')).toBeTruthy());
    expect(screen.queryByRole('link', { name: '💬 모임 채팅' })).toBeNull();
  });
});
