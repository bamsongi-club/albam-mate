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
  let reject;
  const promise = new Promise((nextResolve, nextReject) => {
    resolve = nextResolve;
    reject = nextReject;
  });
  return { promise, resolve, reject };
}

function changeInputImmediately(input, value) {
  const nativeValueSetter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
  nativeValueSetter.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

beforeEach(() => {
  vi.useFakeTimers();
  getGames.mockReset();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

it('검색어가 바뀐 직후 도착한 이전 더 보기 응답을 새 결과에 합치지 않는다', async () => {
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

  await act(async () => {
    changeInputImmediately(input, 'B');
    nextA.resolve({ ...GAME_A_FIRST_PAGE, content: [{ id: 3, name: 'A 늦은 두 번째 게임' }], page: 1, hasNext: false });
  });
  expect(screen.queryByText('A 늦은 두 번째 게임')).toBeNull();
  expect(screen.queryByText('불러오는 중…')).toBeNull();
  act(() => { vi.advanceTimersByTime(250); });
  await act(async () => {});
  expect(screen.getByText('B 첫 번째 게임')).toBeTruthy();

  expect(screen.getByText('B 첫 번째 게임')).toBeTruthy();
  expect(screen.queryByText('A 늦은 두 번째 게임')).toBeNull();
});

it('검색어가 바뀐 직후 도착한 이전 초기 검색 오류를 새 검색 상태에 반영하지 않는다', async () => {
  const firstA = deferred();
  const firstB = deferred();
  getGames.mockImplementation(({ keyword, page }) => {
    if (keyword === 'A' && page === 0) return firstA.promise;
    if (keyword === 'B' && page === 0) return firstB.promise;
    throw new Error('예상하지 못한 페이지 요청');
  });

  render(<GamePickerDialog isOpen selectedGameId="" allowClear={false} onSelect={vi.fn()} onClear={vi.fn()} onClose={vi.fn()} />);
  const input = screen.getByRole('textbox', { name: '게임 이름 검색' });

  fireEvent.change(input, { target: { value: 'A' } });
  act(() => { vi.advanceTimersByTime(250); });
  expect(getGames).toHaveBeenLastCalledWith({ keyword: 'A', page: 0, size: 10 }, expect.any(AbortSignal));

  await act(async () => {
    changeInputImmediately(input, 'B');
    firstA.reject(new Error('A 검색 실패'));
  });

  expect(screen.queryByText('A 검색 실패')).toBeNull();
  expect(screen.queryByText('검색 중…')).toBeNull();

  act(() => { vi.advanceTimersByTime(250); });
  await act(async () => { firstB.resolve(GAME_B_FIRST_PAGE); });

  expect(screen.getByText('B 첫 번째 게임')).toBeTruthy();
  expect(screen.queryByText('A 검색 실패')).toBeNull();
});

it('검색어가 바뀐 직후 도착한 이전 초기 검색 응답을 새 결과에 반영하지 않는다', async () => {
  const firstA = deferred();
  const firstB = deferred();
  getGames.mockImplementation(({ keyword, page }) => {
    if (keyword === 'A' && page === 0) return firstA.promise;
    if (keyword === 'B' && page === 0) return firstB.promise;
    throw new Error('예상하지 못한 페이지 요청');
  });

  render(<GamePickerDialog isOpen selectedGameId="" allowClear={false} onSelect={vi.fn()} onClear={vi.fn()} onClose={vi.fn()} />);
  const input = screen.getByRole('textbox', { name: '게임 이름 검색' });

  fireEvent.change(input, { target: { value: 'A' } });
  act(() => { vi.advanceTimersByTime(250); });

  await act(async () => {
    changeInputImmediately(input, 'B');
    firstA.resolve({ ...GAME_A_FIRST_PAGE, content: [{ id: 3, name: 'A 늦은 첫 번째 게임' }] });
  });

  expect(screen.queryByText('A 늦은 첫 번째 게임')).toBeNull();

  act(() => { vi.advanceTimersByTime(250); });
  await act(async () => { firstB.resolve(GAME_B_FIRST_PAGE); });

  expect(screen.getByText('B 첫 번째 게임')).toBeTruthy();
  expect(screen.queryByText('A 늦은 첫 번째 게임')).toBeNull();
});
