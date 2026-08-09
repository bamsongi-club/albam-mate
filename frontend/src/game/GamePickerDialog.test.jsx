import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const getGames = vi.fn();

vi.mock('../api', () => ({
  api: {
    getGames: (...parameters) => getGames(...parameters)
  },
  messageForError: (error, fallback) => error?.message || fallback
}));

const { GamePickerDialog } = await import('./GamePickerDialog.jsx');

const GAME_A_FIRST_PAGE = {
  content: [{ id: 1, name: 'A 첫 번째 게임' }],
  page: 0,
  size: 10,
  totalElements: 2,
  totalPages: 2,
  hasNext: true
};
const GAME_B_FIRST_PAGE = {
  content: [{ id: 2, name: 'B 첫 번째 게임' }],
  page: 0,
  size: 10,
  totalElements: 1,
  totalPages: 1,
  hasNext: false
};

function deferred() {
  let resolve;
  const promise = new Promise((nextResolve) => { resolve = nextResolve; });
  return { promise, resolve };
}

beforeEach(() => {
  vi.useFakeTimers();
  getGames.mockReset();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

it('검색어가 바뀐 뒤 도착한 이전 더 보기 응답을 새 결과에 합치지 않는다', async () => {
  const firstA = deferred();
  const nextA = deferred();
  getGames.mockImplementation(({ keyword, page }) => {
    if (keyword === 'A' && page === 0) return firstA.promise;
    if (keyword === 'A' && page === 1) return nextA.promise;
    if (keyword === 'B' && page === 0) return Promise.resolve(GAME_B_FIRST_PAGE);
    throw new Error('예상하지 못한 페이지 요청');
  });

  render(<GamePickerDialog isOpen selectedGameId="" allowClear={false} onSelect={vi.fn()} onClear={vi.fn()} onClose={vi.fn()} />);
  const input = screen.getByRole('textbox', { name: '게임 이름 검색' });

  fireEvent.change(input, { target: { value: 'A' } });
  act(() => { vi.advanceTimersByTime(250); });
  await act(async () => { firstA.resolve(GAME_A_FIRST_PAGE); });

  fireEvent.click(screen.getByRole('button', { name: '검색 결과 더 보기' }));
  expect(getGames).toHaveBeenLastCalledWith({ keyword: 'A', page: 1, size: 10 }, expect.any(AbortSignal));

  fireEvent.change(input, { target: { value: 'B' } });
  act(() => { vi.advanceTimersByTime(250); });
  await act(async () => {});
  expect(screen.getByText('B 첫 번째 게임')).toBeTruthy();

  await act(async () => { nextA.resolve({ ...GAME_A_FIRST_PAGE, content: [{ id: 3, name: 'A 늦은 두 번째 게임' }], page: 1, hasNext: false }); });

  expect(screen.getByText('B 첫 번째 게임')).toBeTruthy();
  expect(screen.queryByText('A 늦은 두 번째 게임')).toBeNull();
});
