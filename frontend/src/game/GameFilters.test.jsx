import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const getGameMechanisms = vi.fn();
const getGameCategories = vi.fn();
const getGameThemes = vi.fn();

vi.mock('../api', () => ({
  api: {
    getGameMechanisms: (...parameters) => getGameMechanisms(...parameters),
    getGameCategories: (...parameters) => getGameCategories(...parameters),
    getGameThemes: (...parameters) => getGameThemes(...parameters)
  }
}));

const { GameFilters } = await import('./GameFilters.jsx');
const { EMPTY_GAME_FILTERS } = await import('./constants.js');

beforeEach(() => {
  getGameMechanisms.mockReset();
  getGameMechanisms.mockResolvedValue([]);
  getGameCategories.mockReset();
  getGameCategories.mockResolvedValue([]);
  getGameThemes.mockReset();
  getGameThemes.mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
});

async function openFilterSheet(resultCount) {
  render(<GameFilters filters={EMPTY_GAME_FILTERS} onChange={vi.fn()} resultCount={resultCount} />);
  await act(async () => {});
  fireEvent.click(screen.getByRole('button', { name: /게임 필터/ }));
}

describe('T8 필터 없는 상태의 결과 수를 CTA에 반영한다', () => {
  it('resultCount가 있으면 N개 게임 보기로 표시한다', async () => {
    await openFilterSheet(42);

    expect(screen.getByRole('button', { name: '42개 게임 보기' })).toBeTruthy();
  });

  it('resultCount가 없으면 기본 문구를 유지한다', async () => {
    await openFilterSheet(undefined);

    expect(screen.getByRole('button', { name: '게임 보기' })).toBeTruthy();
  });
});
