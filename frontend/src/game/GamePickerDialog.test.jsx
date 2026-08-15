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

it('닫힌 직후 이전 초기 검색 응답을 재오픈 상태에 반영하지 않는다', async () => {
  const firstA = deferred();
  let firstASignal;
  getGames.mockImplementation(({ keyword, page }, signal) => {
    if (keyword === 'A' && page === 0) {
      firstASignal = signal;
      return firstA.promise;
    }
    throw new Error('예상하지 못한 페이지 요청');
  });

  const onClose = vi.fn();
  const view = render(<GamePickerDialog isOpen selectedGameId="" allowClear={false} onSelect={vi.fn()} onClear={vi.fn()} onClose={onClose} />);
  const input = screen.getByRole('textbox', { name: '게임 이름 검색' });

  fireEvent.change(input, { target: { value: 'A' } });
  act(() => { vi.advanceTimersByTime(250); });
  expect(getGames).toHaveBeenLastCalledWith({ keyword: 'A', page: 0, size: 10 }, expect.any(AbortSignal));

  fireEvent.click(screen.getByRole('button', { name: '게임 검색 닫기' }));
  expect(firstASignal.aborted).toBe(true);
  await act(async () => {
    firstA.resolve({ ...GAME_A_FIRST_PAGE, content: [{ id: 3, name: 'A 닫힌 뒤 응답' }] });
  });
  expect(screen.queryByText('A 닫힌 뒤 응답')).toBeNull();

  view.rerender(<GamePickerDialog isOpen={false} selectedGameId="" allowClear={false} onSelect={vi.fn()} onClear={vi.fn()} onClose={onClose} />);
  view.rerender(<GamePickerDialog isOpen selectedGameId="" allowClear={false} onSelect={vi.fn()} onClear={vi.fn()} onClose={onClose} />);
  expect(screen.getByRole('textbox', { name: '게임 이름 검색' }).value).toBe('');
  expect(screen.queryByText('A 닫힌 뒤 응답')).toBeNull();
});

it('닫힌 직후 이전 초기 검색 오류를 재오픈 상태에 반영하지 않는다', async () => {
  const firstA = deferred();
  getGames.mockImplementation(({ keyword, page }) => {
    if (keyword === 'A' && page === 0) return firstA.promise;
    throw new Error('예상하지 못한 페이지 요청');
  });

  const onClose = vi.fn();
  const view = render(<GamePickerDialog isOpen selectedGameId="" allowClear={false} onSelect={vi.fn()} onClear={vi.fn()} onClose={onClose} />);
  const input = screen.getByRole('textbox', { name: '게임 이름 검색' });

  fireEvent.change(input, { target: { value: 'A' } });
  act(() => { vi.advanceTimersByTime(250); });
  fireEvent.click(screen.getByRole('button', { name: '게임 검색 닫기' }));
  await act(async () => {
    firstA.reject(new Error('A 닫힌 뒤 검색 실패'));
  });
  expect(screen.queryByText('A 닫힌 뒤 검색 실패')).toBeNull();

  view.rerender(<GamePickerDialog isOpen={false} selectedGameId="" allowClear={false} onSelect={vi.fn()} onClear={vi.fn()} onClose={onClose} />);
  view.rerender(<GamePickerDialog isOpen selectedGameId="" allowClear={false} onSelect={vi.fn()} onClear={vi.fn()} onClose={onClose} />);
  expect(screen.queryByText('A 닫힌 뒤 검색 실패')).toBeNull();
  expect(screen.queryByText('검색 중…')).toBeNull();
});

it('닫힌 직후 이전 더 보기 응답을 재오픈 상태에 반영하지 않는다', async () => {
  const firstA = deferred();
  const nextA = deferred();
  let nextASignal;
  getGames.mockImplementation(({ keyword, page }, signal) => {
    if (keyword === 'A' && page === 0) return firstA.promise;
    if (keyword === 'A' && page === 1) {
      nextASignal = signal;
      return nextA.promise;
    }
    throw new Error('예상하지 못한 페이지 요청');
  });

  const onClose = vi.fn();
  const view = render(<GamePickerDialog isOpen selectedGameId="" allowClear={false} onSelect={vi.fn()} onClear={vi.fn()} onClose={onClose} />);
  const input = screen.getByRole('textbox', { name: '게임 이름 검색' });

  fireEvent.change(input, { target: { value: 'A' } });
  act(() => { vi.advanceTimersByTime(250); });
  await act(async () => { firstA.resolve(GAME_A_FIRST_PAGE); });
  fireEvent.click(screen.getByRole('button', { name: '검색 결과 더 보기' }));
  fireEvent.click(screen.getByRole('button', { name: '게임 검색 닫기' }));
  expect(nextASignal.aborted).toBe(true);
  await act(async () => {
    nextA.resolve({ ...GAME_A_FIRST_PAGE, content: [{ id: 3, name: 'A 닫힌 뒤 더 보기 응답' }], page: 1, hasNext: false });
  });
  expect(screen.queryByText('A 닫힌 뒤 더 보기 응답')).toBeNull();

  view.rerender(<GamePickerDialog isOpen={false} selectedGameId="" allowClear={false} onSelect={vi.fn()} onClear={vi.fn()} onClose={onClose} />);
  view.rerender(<GamePickerDialog isOpen selectedGameId="" allowClear={false} onSelect={vi.fn()} onClear={vi.fn()} onClose={onClose} />);
  expect(screen.getByRole('textbox', { name: '게임 이름 검색' }).value).toBe('');
  expect(screen.queryByText('A 닫힌 뒤 더 보기 응답')).toBeNull();
});

it('닫힌 직후 이전 더 보기 오류를 재오픈 상태에 반영하지 않는다', async () => {
  const firstA = deferred();
  const nextA = deferred();
  getGames.mockImplementation(({ keyword, page }) => {
    if (keyword === 'A' && page === 0) return firstA.promise;
    if (keyword === 'A' && page === 1) return nextA.promise;
    throw new Error('예상하지 못한 페이지 요청');
  });

  const onClose = vi.fn();
  const view = render(<GamePickerDialog isOpen selectedGameId="" allowClear={false} onSelect={vi.fn()} onClear={vi.fn()} onClose={onClose} />);
  const input = screen.getByRole('textbox', { name: '게임 이름 검색' });

  fireEvent.change(input, { target: { value: 'A' } });
  act(() => { vi.advanceTimersByTime(250); });
  await act(async () => { firstA.resolve(GAME_A_FIRST_PAGE); });
  fireEvent.click(screen.getByRole('button', { name: '검색 결과 더 보기' }));
  fireEvent.click(screen.getByRole('button', { name: '게임 검색 닫기' }));
  await act(async () => {
    nextA.reject(new Error('A 닫힌 뒤 더 보기 실패'));
  });
  expect(screen.queryByText('A 닫힌 뒤 더 보기 실패')).toBeNull();

  view.rerender(<GamePickerDialog isOpen={false} selectedGameId="" allowClear={false} onSelect={vi.fn()} onClear={vi.fn()} onClose={onClose} />);
  view.rerender(<GamePickerDialog isOpen selectedGameId="" allowClear={false} onSelect={vi.fn()} onClear={vi.fn()} onClose={onClose} />);
  expect(screen.queryByText('A 닫힌 뒤 더 보기 실패')).toBeNull();
  expect(screen.queryByText('검색 중…')).toBeNull();
});

// #750 T4. 필터 시트와 같은 손잡이를 쓰므로 닫기 제스처도 같은 규칙을 따라야 한다.
describe('#750 T4 게임 선택 시트 스와이프 닫기', () => {
  const pointerAt = (clientY) => ({ clientY, pointerId: 1 });
  const grip = () => document.querySelector('.sheet-grip');
  const SHEET_CLOSE_DRAG_PX = 80;

  function openPicker(onClose) {
    getGames.mockResolvedValue(GAME_A_FIRST_PAGE);
    return render(
      <GamePickerDialog isOpen selectedGameId={null} allowClear={false} onSelect={vi.fn()} onClear={vi.fn()} onClose={onClose} />
    );
  }

  it('손잡이에서 임계값 이상 끌면 닫힌다', async () => {
    const onClose = vi.fn();
    openPicker(onClose);
    await act(async () => {});

    fireEvent.pointerDown(grip(), pointerAt(100));
    fireEvent.pointerMove(grip(), pointerAt(100 + SHEET_CLOSE_DRAG_PX));
    fireEvent.pointerUp(grip(), pointerAt(100 + SHEET_CLOSE_DRAG_PX));

    expect(onClose).toHaveBeenCalled();
  });

  it('임계값 미만으로 끌었다 놓으면 닫히지 않는다', async () => {
    const onClose = vi.fn();
    openPicker(onClose);
    await act(async () => {});

    fireEvent.pointerDown(grip(), pointerAt(100));
    fireEvent.pointerMove(grip(), pointerAt(100 + SHEET_CLOSE_DRAG_PX - 1));
    fireEvent.pointerUp(grip(), pointerAt(100 + SHEET_CLOSE_DRAG_PX - 1));

    expect(onClose).not.toHaveBeenCalled();
    expect(document.querySelector('.sheet').style.transform).toBe('');
  });
});
