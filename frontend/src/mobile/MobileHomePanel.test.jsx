import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../api';
import { MobileHomePanel } from './MobileHomePanel';

const ME = { id: 1, nickname: '테스터' };

afterEach(() => {
  vi.restoreAllMocks();
  cleanup();
});

beforeEach(() => {
  // 홈은 오늘 열리는 모임과 인기 랭킹도 함께 부른다. 여기서는 내 모임 카드만 확인한다.
  vi.spyOn(api, 'getRooms').mockResolvedValue(page([]));
  vi.spyOn(api, 'getGameRankings').mockResolvedValue({ overall: [], pastWeek: [] });
});

function room(overrides) {
  return {
    id: 1,
    title: '주말 윙스팬',
    roomType: 'GAME_FOCUSED',
    status: 'RECRUITING',
    startsAt: '2099-09-01T19:00:00+09:00',
    place: '강남 보드게임 카페',
    region: '강남',
    experienceLevel: 'BEGINNER_WELCOME',
    isRulemasterLed: false,
    participantCount: 2,
    recruitmentCapacity: 4,
    remainingRecruitmentSeats: 3,
    participants: [],
    game: null,
    myRole: 'JOINED',
    participationStatus: 'ACTIVE',
    chatAvailable: true,
    ...overrides
  };
}

function page(content) {
  return {
    content,
    page: 0,
    size: 100,
    totalElements: content.length,
    totalPages: 1
  };
}

describe('MobileHomePanel', () => {
  it('참가·개설 모임 중 취소되지 않은 가장 이른 예정 모임을 표시한다', async () => {
    const canceledRoom = room({ id: 2, title: '취소된 더 이른 모임', status: 'CANCELED', startsAt: '2099-08-01T19:00:00+09:00' });
    const nextRoom = room({ id: 3, title: '다음 내 모임', startsAt: '2099-09-01T19:00:00+09:00' });
    const laterRoom = room({ id: 4, title: '더 나중 모임', startsAt: '2099-10-01T19:00:00+09:00', myRole: 'HOST' });
    const getMyRooms = vi.spyOn(api, 'getMyRooms').mockImplementation(({ role }) => (
      Promise.resolve(page(role === 'joined' ? [canceledRoom, laterRoom] : [nextRoom]))
    ));

    render(<MobileHomePanel me={ME} dataVersion={0} />);

    await waitFor(() => expect(screen.getByRole('link', { name: '다음 내 모임' })).toBeTruthy());
    expect(screen.queryByText('취소된 더 이른 모임')).toBeNull();
    expect(screen.getByRole('link', { name: '모임 채팅' }).getAttribute('href')).toBe('#/chat/3');
    expect(screen.getByRole('link', { name: '상세 보기' }).getAttribute('href')).toBe('#/session/3');
    expect(screen.getByRole('link', { name: '내 모임 전체' }).getAttribute('href')).toBe('#/my');
    expect(getMyRooms).toHaveBeenCalledWith({ role: 'joined', page: 0, size: 100 }, expect.anything());
    expect(getMyRooms).toHaveBeenCalledWith({ role: 'hosted', page: 0, size: 100 }, expect.anything());
  });

  it('예정된 내 모임이 없으면 탐색과 만들기 CTA를 보인다', async () => {
    vi.spyOn(api, 'getMyRooms').mockResolvedValue(page([]));

    render(<MobileHomePanel me={ME} dataVersion={0} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '예정된 모임이 없어요' })).toBeTruthy());
    expect(screen.getByRole('link', { name: '모임 찾아보기' }).getAttribute('href')).toBe('#/find');
    expect(screen.getByRole('link', { name: '모임 만들기' }).getAttribute('href')).toBe('#/create');
  });

  it('비로그인 상태에서는 내 모임을 조회하지 않는다', async () => {
    const getMyRooms = vi.spyOn(api, 'getMyRooms').mockResolvedValue(page([]));

    render(<MobileHomePanel me={null} dataVersion={0} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '오늘 열리는 모임' })).toBeTruthy());
    expect(getMyRooms).not.toHaveBeenCalled();
  });
});
