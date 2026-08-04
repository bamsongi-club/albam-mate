import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const getGames = vi.fn();

vi.mock('./api', () => ({
  ApiError: class ApiError extends Error {},
  api: {
    getGames: (...parameters) => getGames(...parameters),
    getMyProfile: vi.fn(),
    getNotifications: vi.fn(),
    getUnreadNotificationCount: vi.fn()
  },
  clearCsrfToken: vi.fn(),
  messageForError: () => '요청을 처리하지 못했어요.',
  setUnauthenticatedHandler: vi.fn()
}));

const { GamesView } = await import('./main.jsx');

const EMPTY_PAGE = { content: [], page: 0, size: 24, totalElements: 0, totalPages: 0 };

function lastQuery() {
  return getGames.mock.calls.at(-1)[0];
}

function renderGamesView() {
  return render(
    <GamesView title="게임 찾기" gameQuery="" onGameQueryChange={vi.fn()} dataVersion={0} />
  );
}

function openFilterPanel() {
  fireEvent.click(screen.getByLabelText('조건 필터'));
}

beforeEach(() => {
  vi.useFakeTimers();
  getGames.mockReset();
  getGames.mockResolvedValue(EMPTY_PAGE);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('T2·T3 게임 조건 필터 조회 시점', () => {
  it('숫자 입력은 마지막 입력 0.4초가 지난 뒤에만 조회한다', () => {
    renderGamesView();
    openFilterPanel();
    const callsBeforeTyping = getGames.mock.calls.length;

    fireEvent.change(screen.getByLabelText('최소'), { target: { value: '2' } });
    act(() => { vi.advanceTimersByTime(399); });
    expect(getGames.mock.calls.length).toBe(callsBeforeTyping);

    fireEvent.change(screen.getByLabelText('최소'), { target: { value: '3' } });
    act(() => { vi.advanceTimersByTime(399); });
    expect(getGames.mock.calls.length).toBe(callsBeforeTyping);

    act(() => { vi.advanceTimersByTime(1); });
    expect(getGames.mock.calls.length).toBe(callsBeforeTyping + 1);
    expect(lastQuery().playerCountMin).toBe('3');
  });

  it('체크박스 선택·해제는 기다리지 않고 바로 조회한다', () => {
    renderGamesView();
    openFilterPanel();
    const callsBeforeToggle = getGames.mock.calls.length;

    fireEvent.click(screen.getByLabelText('90분 이상'));

    expect(getGames.mock.calls.length).toBe(callsBeforeToggle + 1);
    expect(lastQuery().playTime).toEqual(['AT_LEAST_90']);
  });

  it('플레이 시간은 확정한 6구간만 제공하고 제거된 구간을 남기지 않는다', () => {
    renderGamesView();
    openFilterPanel();

    ['10분 이내', '10~20분', '20~30분', '30~60분', '60~90분', '90분 이상'].forEach((label) => {
      expect(screen.getByLabelText(label)).toBeTruthy();
    });
    ['20분 이하', '20분 초과 60분 이하', '60분 초과'].forEach((label) => {
      expect(screen.queryByLabelText(label)).toBeNull();
    });
  });

  it('플레이 시간 여러 구간을 함께 선택하면 선택한 값을 모두 전달한다', () => {
    renderGamesView();
    openFilterPanel();

    fireEvent.click(screen.getByLabelText('10분 이내'));
    fireEvent.click(screen.getByLabelText('90분 이상'));

    expect(lastQuery().playTime).toEqual(['UP_TO_10', 'AT_LEAST_90']);
  });

  it('필터 영역을 닫았다 다시 열어도 입력과 선택을 유지한다', () => {
    renderGamesView();
    openFilterPanel();
    fireEvent.change(screen.getByLabelText('최소'), { target: { value: '2' } });
    fireEvent.click(screen.getByLabelText('90분 이상'));

    fireEvent.click(screen.getByText('닫기'));
    expect(screen.queryByLabelText('최소')).toBeNull();

    openFilterPanel();

    expect(screen.getByLabelText('최소').value).toBe('2');
    expect(screen.getByLabelText('90분 이상').checked).toBe(true);
  });
});

describe('T1 인원 범위와 전용 인원의 화면 전환', () => {
  it('전용 인원을 하나 고르면 그 인원의 범위와 정확히 일치를 화면에 되비춘다', () => {
    renderGamesView();
    openFilterPanel();

    fireEvent.click(screen.getByLabelText('1인 전용'));

    expect(screen.getByLabelText('최소').value).toBe('1');
    expect(screen.getByLabelText('최대').value).toBe('1');
    expect(screen.getByLabelText('인원 정확히 일치').checked).toBe(true);
  });

  it('되비춘 범위는 요청에 담지 않고 전용 인원 조건만 보낸다', () => {
    renderGamesView();
    openFilterPanel();

    fireEvent.click(screen.getByLabelText('2인 전용'));
    act(() => { vi.advanceTimersByTime(400); });

    expect(lastQuery().exclusivePlayerCount).toEqual(['2']);
    expect(lastQuery().playerCountMin).toBe('');
    expect(lastQuery().playerCountMax).toBe('');
    expect(lastQuery().playerCountExact).toBe(false);
  });

  it('전용 인원을 둘 다 고르면 범위 입력을 비우고 두 값을 함께 보낸다', () => {
    renderGamesView();
    openFilterPanel();

    fireEvent.click(screen.getByLabelText('1인 전용'));
    fireEvent.click(screen.getByLabelText('2인 전용'));
    act(() => { vi.advanceTimersByTime(400); });

    expect(screen.getByLabelText('최소').value).toBe('');
    expect(screen.getByLabelText('최대').value).toBe('');
    expect(screen.getByLabelText('인원 정확히 일치').checked).toBe(false);
    expect(lastQuery().exclusivePlayerCount).toEqual(['1', '2']);
  });

  it('전용 인원 하나를 해제하면 남은 하나를 다시 범위에 되비춘다', () => {
    renderGamesView();
    openFilterPanel();
    fireEvent.click(screen.getByLabelText('1인 전용'));
    fireEvent.click(screen.getByLabelText('2인 전용'));

    fireEvent.click(screen.getByLabelText('1인 전용'));

    expect(screen.getByLabelText('최소').value).toBe('2');
    expect(screen.getByLabelText('최대').value).toBe('2');
    expect(screen.getByLabelText('인원 정확히 일치').checked).toBe(true);
  });

  it('전용 인원으로 전환할 때 범위와 전용 인원을 함께 담은 요청을 보내지 않는다', () => {
    renderGamesView();
    openFilterPanel();
    fireEvent.change(screen.getByLabelText('최소'), { target: { value: '2' } });
    fireEvent.change(screen.getByLabelText('최대'), { target: { value: '4' } });
    act(() => { vi.advanceTimersByTime(400); });

    fireEvent.click(screen.getByLabelText('1인 전용'));
    act(() => { vi.advanceTimersByTime(400); });

    const invalidCalls = getGames.mock.calls.filter(([parameters]) =>
      parameters.exclusivePlayerCount.length > 0
        && (parameters.playerCountMin !== '' || parameters.playerCountMax !== ''));
    expect(invalidCalls).toEqual([]);
  });

  it('인원 범위를 입력하면 선택한 전용 인원을 해제한다', () => {
    renderGamesView();
    openFilterPanel();
    fireEvent.click(screen.getByLabelText('2인 전용'));

    fireEvent.change(screen.getByLabelText('최소'), { target: { value: '3' } });
    act(() => { vi.advanceTimersByTime(400); });

    expect(screen.getByLabelText('2인 전용').checked).toBe(false);
    expect(lastQuery().exclusivePlayerCount).toEqual([]);
    expect(lastQuery().playerCountMin).toBe('3');
  });

  it('전용 인원에서 범위로 전환하면 되비추던 최대와 정확히 일치를 남기지 않는다', () => {
    renderGamesView();
    openFilterPanel();
    fireEvent.click(screen.getByLabelText('2인 전용'));

    fireEvent.change(screen.getByLabelText('최소'), { target: { value: '3' } });
    act(() => { vi.advanceTimersByTime(400); });

    expect(screen.getByLabelText('최대').value).toBe('');
    expect(screen.getByLabelText('인원 정확히 일치').checked).toBe(false);
    expect(lastQuery().playerCountMax).toBe('');
    expect(lastQuery().playerCountExact).toBe(false);
  });

  it('전용 인원에서 정확히 일치만 해제해도 되비추던 범위를 남기지 않는다', () => {
    renderGamesView();
    openFilterPanel();
    fireEvent.click(screen.getByLabelText('2인 전용'));

    fireEvent.click(screen.getByLabelText('인원 정확히 일치'));
    act(() => { vi.advanceTimersByTime(400); });

    expect(screen.getByLabelText('최소').value).toBe('');
    expect(screen.getByLabelText('최대').value).toBe('');
    expect(lastQuery().playerCountMin).toBe('');
    expect(lastQuery().playerCountMax).toBe('');
  });
});
