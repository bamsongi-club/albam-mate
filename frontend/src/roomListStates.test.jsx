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
    expect(screen.getByRole('link', { name: '모임 만들기' }).getAttribute('href')).toBe('#/create');

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
