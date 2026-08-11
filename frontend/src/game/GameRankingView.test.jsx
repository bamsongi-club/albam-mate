import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const getGameRankings = vi.fn();

vi.mock('../api', () => ({
  api: {
    getGameRankings: (...parameters) => getGameRankings(...parameters)
  },
  messageForError: (error, fallback = '요청을 처리하지 못했어요.') => error?.message || fallback
}));

const { GameRankingView } = await import('./GameRankingView.jsx');

// 순위·이름·집계 수가 한 항목 안에 나뉘어 있어 행 전체 텍스트로 확인한다.
function rowText(name) {
  return screen.getByRole('link', { name: new RegExp(name) }).textContent;
}

const RANKINGS = {
  overall: [
    { rank: 1, gameId: 7, bggId: 13, name: '카탄', imageUrl: null, roomCount: 12 },
    { rank: 2, gameId: 9, bggId: 822, name: '카르카손', imageUrl: null, roomCount: 5 }
  ],
  upcomingWeek: [
    { rank: 1, gameId: 9, bggId: 822, name: '카르카손', imageUrl: null, roomCount: 3 }
  ]
};

beforeEach(() => {
  getGameRankings.mockReset();
  getGameRankings.mockResolvedValue(RANKINGS);
});

afterEach(cleanup);

describe('인기 게임 랭킹 화면', () => {
  it('전체와 앞으로 7일을 탭으로 구분해 표시하고 전환에 다시 조회하지 않는다', async () => {
    render(<GameRankingView dataVersion={0} />);
    await act(async () => {});

    expect(screen.getByRole('button', { name: '전체' }).getAttribute('aria-pressed')).toBe('true');
    expect(rowText('카탄')).toContain('모임 12개');
    expect(rowText('카르카손')).toContain('모임 5개');

    fireEvent.click(screen.getByRole('button', { name: '앞으로 7일' }));

    expect(screen.getByRole('button', { name: '앞으로 7일' }).getAttribute('aria-pressed')).toBe('true');
    expect(rowText('카르카손')).toContain('모임 3개');
    expect(screen.queryByRole('link', { name: /카탄/ })).toBeNull();
    expect(getGameRankings.mock.calls.length).toBe(1);
  });

  it('첫 조회 중에는 로딩 상태를 표시한다', () => {
    getGameRankings.mockReturnValue(new Promise(() => {}));
    render(<GameRankingView dataVersion={0} />);

    expect(screen.getByText('불러오는 중…')).toBeTruthy();
  });

  it('랭킹이 비어 있으면 탭마다 다른 안내를 표시한다', async () => {
    getGameRankings.mockResolvedValue({ overall: [], upcomingWeek: [] });
    render(<GameRankingView dataVersion={0} />);
    await act(async () => {});

    expect(screen.getByText('아직 집계할 모임이 없어요. 첫 모임을 열어보세요.')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '앞으로 7일' }));

    expect(screen.getByText('앞으로 7일 안에 시작하는 모임이 없어요.')).toBeTruthy();
  });

  it('조회가 실패하면 오류를 알리고 다시 시도할 수 있다', async () => {
    getGameRankings.mockRejectedValue(new Error('랭킹을 불러오지 못했어요.'));
    render(<GameRankingView dataVersion={0} />);
    await act(async () => {});

    expect(screen.getByRole('alert').textContent).toContain('랭킹을 불러오지 못했어요.');

    getGameRankings.mockResolvedValue(RANKINGS);
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await act(async () => {});

    expect(getGameRankings.mock.calls.length).toBe(2);
    expect(screen.queryByRole('alert')).toBeNull();
    expect(rowText('카탄')).toContain('모임 12개');
  });

  it('랭킹 항목은 해당 게임 상세로 이동한다', async () => {
    render(<GameRankingView dataVersion={0} />);
    await act(async () => {});

    expect(screen.getByRole('link', { name: /카탄/ }).getAttribute('href')).toBe('#/game/7');
    expect(screen.getByRole('link', { name: /카르카손/ }).getAttribute('href')).toBe('#/game/9');
  });
});
