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
  // 홈은 모임 현황과 인기 랭킹, 모집 중이 없을 때의 게임 목록까지 함께 부른다.
  vi.spyOn(api, 'getRooms').mockResolvedValue(page([]));
  vi.spyOn(api, 'getGameRankings').mockResolvedValue({ overall: [], pastWeek: [] });
  vi.spyOn(api, 'getGames').mockResolvedValue(page([]));
  vi.spyOn(api, 'getMyRooms').mockResolvedValue(page([]));
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

function page(content, totalElements) {
  return {
    content,
    page: 0,
    size: 100,
    totalElements: totalElements ?? content.length,
    totalPages: 1
  };
}

// 오늘 조회는 종료 경계를 함께 보내고, 앞으로 열리는 모임 조회는 시작 경계만 보낸다.
function mockOpenRooms({ today = [], upcoming = today, recruitingCount }) {
  vi.spyOn(api, 'getRooms').mockImplementation(({ startsAtTo }) => (
    Promise.resolve(startsAtTo ? page(today) : page(upcoming, recruitingCount))
  ));
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

  it('참가하는 모임만 없으면 모집 중 건수를 알리고 찾아보기를 주 버튼으로 둔다', async () => {
    mockOpenRooms({ today: [room({ id: 5 })], upcoming: [room({ id: 5 })], recruitingCount: 7 });

    render(<MobileHomePanel me={ME} dataVersion={0} />);

    await waitFor(() => expect(screen.getByText('지금 모집 중인 모임 7개')).toBeTruthy());
    expect(screen.getByRole('heading', { name: '아직 참가하는 모임이 없어요' })).toBeTruthy();
    expect(screen.getByRole('link', { name: '모임 찾아보기' }).getAttribute('href')).toBe('#/find');
    expect(screen.getByRole('link', { name: '모임 만들기' }).getAttribute('href')).toBe('#/create');
  });

  it('오늘 열리는 모임만 없으면 곧 열리는 모임으로 목록을 채운다', async () => {
    mockOpenRooms({ today: [], upcoming: [room({ id: 9, title: '다음 주 카탄' })] });

    render(<MobileHomePanel me={ME} dataVersion={0} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '곧 열리는 모임' })).toBeTruthy());
    expect(screen.getByText('다음 주 카탄')).toBeTruthy();
    expect(screen.getByRole('link', { name: '모두 보기' }).getAttribute('href')).toBe('#/find');
  });

  it('모집 중인 모임이 아예 없으면 만들기를 주 버튼으로 올리고 게임 고르기로 자리를 채운다', async () => {
    vi.spyOn(api, 'getGames').mockResolvedValue(page([
      { id: 11, name: '카르카손', supportedPlayerCount: '2-5인', estimatedPlayTime: '35분' }
    ]));

    render(<MobileHomePanel me={ME} dataVersion={0} />);

    await waitFor(() => expect(screen.getByText('첫 모임을 열어보세요')).toBeTruthy());
    // 모집 중 0개 옆에 '모임 찾아보기'를 두면 빈 목록으로 보내므로 주 버튼에서 뺀다.
    expect(screen.queryByRole('link', { name: '모임 찾아보기' })).toBeNull();
    expect(screen.getByRole('link', { name: '모임 만들기' }).getAttribute('href')).toBe('#/create');
    expect(screen.getByRole('link', { name: '게임 둘러보기' }).getAttribute('href')).toBe('#/game-list');
    expect(screen.getByRole('heading', { name: '어떤 게임으로 여시게요?' })).toBeTruthy();
    expect(screen.getByRole('link', { name: /카르카손/ }).getAttribute('href')).toBe('#/game/11');
    expect(screen.queryByRole('link', { name: '모두 보기' })).toBeNull();
  });

  it('모집 중인 모임이 없으면 랭킹 수치를 전체 건수로 표기한다', async () => {
    vi.spyOn(api, 'getGameRankings').mockResolvedValue({
      overall: [{ gameId: 3, name: '카탄', imageUrl: null, roomCount: 12 }],
      pastWeek: []
    });

    render(<MobileHomePanel me={ME} dataVersion={0} />);

    await waitFor(() => expect(screen.getByText('전체 12개')).toBeTruthy());
    expect(screen.queryByText('열린 모임 12')).toBeNull();
  });

  it('모임 조회가 실패하면 빈 목록 대신 오류와 다시 시도를 보인다', async () => {
    vi.spyOn(api, 'getRooms').mockRejectedValue(new Error('boom'));

    render(<MobileHomePanel me={ME} dataVersion={0} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '모임을 불러오지 못했어요' })).toBeTruthy());
    expect(screen.queryByRole('heading', { name: '어떤 게임으로 여시게요?' })).toBeNull();
  });

  it('랭킹 집계만 실패하면 랭킹 섹션만 오류로 두고 다른 섹션은 살린다', async () => {
    mockOpenRooms({ today: [room({ id: 5, title: '오늘 모임' })] });
    vi.spyOn(api, 'getGameRankings').mockRejectedValue(new Error('boom'));

    render(<MobileHomePanel me={ME} dataVersion={0} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '랭킹을 불러오지 못했어요' })).toBeTruthy());
    expect(screen.getByText('오늘 모임')).toBeTruthy();
    expect(screen.queryByRole('link', { name: '랭킹 전체' })).toBeNull();
  });

  it('비로그인 상태에서는 내 모임을 조회하지 않는다', async () => {
    const getMyRooms = vi.spyOn(api, 'getMyRooms').mockResolvedValue(page([]));
    mockOpenRooms({ today: [room({ id: 5 })] });

    render(<MobileHomePanel me={null} dataVersion={0} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '오늘 열리는 모임' })).toBeTruthy());
    expect(getMyRooms).not.toHaveBeenCalled();
  });
});
