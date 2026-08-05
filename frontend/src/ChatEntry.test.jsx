import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, api } from './api';
import { App, ChatRoomView, MyRoomsSection } from './main';

afterEach(() => {
  vi.restoreAllMocks();
  cleanup();
});

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

describe('#291 T1 방 생성 직후 채팅 진입', () => {
  it('개설한 모임의 chatAvailable 항목에 채팅 라우트 링크를 보여준다', async () => {
    const { getMyRooms } = renderMyRooms(myRoom({ myRole: 'HOST', chatAvailable: true }));

    await waitFor(() => expect(screen.getByText(/홍대 보드게임 모임/)).toBeTruthy());
    expect(getMyRooms).toHaveBeenCalledWith(expect.objectContaining({ role: 'hosted' }), expect.anything());
    expect(screen.queryByText(/다른 탭 모임/)).toBeNull();
    expect(chatEntry().getAttribute('href')).toBe('#/chat/7');
  });
});

describe('#291 T2 ACTIVE 참가 방 채팅 진입', () => {
  it('참가한 모임의 chatAvailable 항목에 채팅 라우트 링크를 보여준다', async () => {
    const { getMyRooms } = renderMyRooms(myRoom({ myRole: 'JOINED', participationStatus: 'ACTIVE', status: 'CLOSED', chatAvailable: true }));

    await waitFor(() => expect(screen.getByText(/홍대 보드게임 모임/)).toBeTruthy());
    expect(getMyRooms).toHaveBeenCalledWith(expect.objectContaining({ role: 'joined' }), expect.anything());
    expect(screen.queryByText(/다른 탭 모임/)).toBeNull();
    expect(chatEntry().getAttribute('href')).toBe('#/chat/7');
  });
});

describe('#291 T3 참가 취소·최종 상태 방', () => {
  it('chatAvailable이 false이면 채팅 진입을 표시하지 않는다', async () => {
    renderMyRooms(myRoom({ status: 'CANCELED', chatAvailable: false }));

    await waitFor(() => expect(screen.getByText('취소됨')).toBeTruthy());
    expect(chatEntry()).toBeNull();
  });

  it('chatAvailable이 없는 응답도 채팅 진입으로 해석하지 않는다', async () => {
    const room = myRoom({ status: 'FINISHED' });
    delete room.chatAvailable;
    renderMyRooms(room);

    await waitFor(() => expect(screen.getByText('종료됨')).toBeTruthy());
    expect(chatEntry()).toBeNull();
  });
});

describe('#291 T4 직접 URL 접근 거절', () => {
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

describe('#291 T4 채팅 라우트 진입', () => {
  it('로그인 상태의 #/chat/{roomId} 진입은 해당 방의 채팅 화면을 연다', async () => {
    stubAppDependencies({ id: 1, nickname: '테스터' });
    const getChatMessages = vi.spyOn(api, 'getChatMessages').mockResolvedValue({
      messages: [{ messageId: 1, roomId: 7, clientMessageId: 'c1', sender: { nickname: '주최자' }, content: '7시에 만나요', createdAt: '2026-09-01T19:00:00+09:00' }],
      nextBeforeMessageId: null,
      hasNext: false
    });
    window.location.hash = '#/chat/7';

    render(<App />);

    await waitFor(() => expect(screen.getByText('7시에 만나요')).toBeTruthy());
    expect(getChatMessages).toHaveBeenCalledWith('7', expect.anything());
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

describe('#291 T5 채팅 화면 이력 표시', () => {
  it('허용된 방의 이력을 오래된 순서로 일반 텍스트로 보여준다', async () => {
    vi.spyOn(api, 'getChatMessages').mockResolvedValue({
      messages: [
        { messageId: 2, roomId: 7, clientMessageId: 'c2', sender: { nickname: '참가자' }, content: '<img src=x onerror=alert(1)>', createdAt: '2026-09-01T19:05:00+09:00' },
        { messageId: 1, roomId: 7, clientMessageId: 'c1', sender: { nickname: '주최자' }, content: '7시에 만나요', createdAt: '2026-09-01T19:00:00+09:00' }
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
});
