import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const getRooms = vi.fn();

vi.mock('./api', () => ({
  ApiError: class TestApiError extends Error {},
  api: { getRooms: (...parameters) => getRooms(...parameters) },
  clearCsrfToken: vi.fn(),
  messageForError: (error, fallback = '요청을 처리하지 못했어요.') => error?.message || fallback,
  setUnauthenticatedHandler: vi.fn()
}));

const { FindRoomsView } = await import('./main.jsx');

const EMPTY_FILTERS = {
  status: '',
  datePreset: '',
  date: '',
  minRemainingSeats: '',
  experienceLevel: '',
  rulemasterOnly: false
};

function emptyPage() {
  return { content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 };
}

function room(id) {
  return {
    id,
    title: '모임 ' + id,
    roomType: 'GAME_FOCUSED',
    status: 'RECRUITING',
    startsAt: '2099-09-01T19:00:00+09:00',
    place: '강남',
    region: '강남',
    experienceLevel: 'BEGINNER_WELCOME',
    isRulemasterLed: false,
    participantCount: 1,
    recruitmentCapacity: 3,
    remainingRecruitmentSeats: 3,
    participants: [],
    game: null
  };
}

function roomPage(ids, { page = 0, total, hasNext = false } = {}) {
  return { content: ids.map(room), page, size: 10, totalElements: total ?? ids.length, totalPages: 2, hasNext };
}

function renderFindRooms(props = {}) {
  return render(
    <FindRoomsView
      roomType=""
      onRoomTypeChange={vi.fn()}
      roomQuery=""
      onRoomQueryChange={vi.fn()}
      roomFilters={EMPTY_FILTERS}
      onRoomFiltersChange={vi.fn()}
      dataVersion={0}
      {...props}
    />
  );
}

beforeEach(() => {
  getRooms.mockReset();
  getRooms.mockResolvedValue(emptyPage());
});

afterEach(cleanup);

describe('모임 찾기 예외 화면', () => {
  it('결과가 없으면 필터 초기화와 모임 만들기를 함께 제공한다', async () => {
    const onRoomTypeChange = vi.fn();
    const onRoomQueryChange = vi.fn();
    const onRoomFiltersChange = vi.fn();
    renderFindRooms({ onRoomTypeChange, onRoomQueryChange, onRoomFiltersChange });
    await act(async () => {});

    expect(screen.getByRole('heading', { name: '조건에 맞는 모임이 없어요' })).toBeTruthy();
    // 타이틀 줄의 + 버튼과 빈 결과 안내가 같은 곳으로 간다.
    screen.getAllByRole('link', { name: '모임 만들기' }).forEach((link) => {
      expect(link.getAttribute('href')).toBe('#/create');
    });

    fireEvent.click(screen.getByRole('button', { name: '필터 초기화' }));
    expect(onRoomTypeChange).toHaveBeenCalledWith('');
    expect(onRoomQueryChange).toHaveBeenCalledWith('');
    expect(onRoomFiltersChange).toHaveBeenCalledWith(EMPTY_FILTERS);
  });

  it('첫 목록을 불러오는 동안 카드 비율 스켈레톤을 표시한다', () => {
    getRooms.mockReturnValue(new Promise(() => {}));
    renderFindRooms();

    expect(screen.getByRole('status', { name: '모임 목록을 불러오는 중' })).toBeTruthy();
  });

  it('번호형 페이지네이션으로 다음 페이지를 조회한다', async () => {
    getRooms.mockImplementation(({ page }) => Promise.resolve(
      page === 0
        ? roomPage([1, 2], { page: 0, total: 3 })
        : roomPage([3], { page: 1, total: 3 })
    ));
    renderFindRooms();
    await act(async () => {});

    expect(screen.getByText('모임 1')).toBeTruthy();
    expect(screen.getByRole('button', { name: '이전 페이지' }).disabled).toBe(true);

    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '다음 페이지' })); });

    // 앞 페이지 아래에 이어 붙이지 않고 다음 페이지로 갈아치운다.
    expect(screen.queryByText('모임 1')).toBeNull();
    expect(screen.getByText('모임 3')).toBeTruthy();
    expect(getRooms).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }), expect.anything());
  });

  it('조건이 바뀌면 첫 페이지부터 다시 조회한다', async () => {
    getRooms.mockResolvedValue(roomPage([1, 2], { total: 5 }));
    const view = renderFindRooms();
    await act(async () => {});
    expect(screen.getByText('모임 1')).toBeTruthy();

    getRooms.mockResolvedValue(roomPage([9], { total: 1 }));
    view.rerender(
      <FindRoomsView
        roomType="GAME_FOCUSED"
        onRoomTypeChange={vi.fn()}
        roomQuery=""
        onRoomQueryChange={vi.fn()}
        roomFilters={EMPTY_FILTERS}
        onRoomFiltersChange={vi.fn()}
        dataVersion={0}
      />
    );
    await act(async () => {});

    expect(screen.queryByText('모임 1')).toBeNull();
    expect(screen.getByText('모임 9')).toBeTruthy();
  });

  it('목록 조회가 실패하면 다시 시도할 수 있다', async () => {
    getRooms.mockRejectedValue(new Error('모임 목록을 불러오지 못했어요.'));
    renderFindRooms();
    await act(async () => {});

    expect(screen.getByText('모임 목록을 불러오지 못했어요.')).toBeTruthy();
    const callsBeforeRetry = getRooms.mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await act(async () => {});
    expect(getRooms.mock.calls.length).toBeGreaterThan(callsBeforeRetry);
  });
});
