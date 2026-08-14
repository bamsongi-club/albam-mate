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
  // 홈은 모임 목록과 인기 랭킹, 랭킹이 비었을 때의 게임 목록도 함께 부른다.
  vi.spyOn(api, 'getRooms').mockResolvedValue(page([]));
  vi.spyOn(api, 'getGameRankings').mockResolvedValue({ overall: [], pastWeek: [] });
  vi.spyOn(api, 'getGames').mockResolvedValue(page([]));
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

  it('오늘 열리는 모임이 없으면 다음에 열리는 모임으로 목록을 채운다', async () => {
    vi.spyOn(api, 'getMyRooms').mockResolvedValue(page([]));
    // 첫 조회는 오늘 범위, 두 번째 조회는 오늘 이후다.
    vi.spyOn(api, 'getRooms').mockImplementation(({ startsAtTo }) => (
      Promise.resolve(page(startsAtTo ? [] : [room({ id: 9, title: '다음 주 카탄' })]))
    ));

    render(<MobileHomePanel me={ME} dataVersion={0} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '곧 열리는 모임' })).toBeTruthy());
    expect(screen.getByText('다음 주 카탄')).toBeTruthy();
  });

  it('열린 모임이 하나도 없으면 목록 자리에 안내를 둔다', async () => {
    vi.spyOn(api, 'getMyRooms').mockResolvedValue(page([]));

    render(<MobileHomePanel me={ME} dataVersion={0} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '오늘 열리는 모임' })).toBeTruthy());
    expect(await screen.findByText(/가장 먼저 모임을 열어보세요/)).toBeTruthy();
  });

  it('랭킹이 비면 게임 목록으로 아래 자리를 채운다', async () => {
    vi.spyOn(api, 'getMyRooms').mockResolvedValue(page([]));
    const getGames = vi.spyOn(api, 'getGames').mockResolvedValue(page([
      { id: 11, name: '카르카손', supportedPlayerCount: '2-5인', estimatedPlayTime: '35분' }
    ]));

    render(<MobileHomePanel me={ME} dataVersion={0} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '게임 둘러보기' })).toBeTruthy());
    expect(screen.getByRole('link', { name: /카르카손/ }).getAttribute('href')).toBe('#/game/11');
    expect(screen.getByRole('link', { name: '게임 전체' }).getAttribute('href')).toBe('#/game-list');
    expect(getGames).toHaveBeenCalledWith({ page: 0, size: 3 }, expect.anything());
  });

  it('비로그인 상태에서는 내 모임을 조회하지 않는다', async () => {
    const getMyRooms = vi.spyOn(api, 'getMyRooms').mockResolvedValue(page([]));

    render(<MobileHomePanel me={null} dataVersion={0} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '오늘 열리는 모임' })).toBeTruthy());
    expect(getMyRooms).not.toHaveBeenCalled();
  });
});
