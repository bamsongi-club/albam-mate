import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const getGames = vi.fn();
const getGameMechanisms = vi.fn();
const getGameCategories = vi.fn();
const getGameThemes = vi.fn();

vi.mock('./api', () => ({
  ApiError: class ApiError extends Error {},
  api: {
    getGames: (...parameters) => getGames(...parameters),
    getGameMechanisms: (...parameters) => getGameMechanisms(...parameters),
    getGameCategories: (...parameters) => getGameCategories(...parameters),
    getGameThemes: (...parameters) => getGameThemes(...parameters),
    getMyProfile: vi.fn(),
    getNotifications: vi.fn(),
    getUnreadNotificationCount: vi.fn()
  },
  clearCsrfToken: vi.fn(),
  messageForError: () => '요청을 처리하지 못했어요.',
  setUnauthenticatedHandler: vi.fn()
}));

const { GamesView } = await import('./game/index.js');

const EMPTY_PAGE = { content: [], page: 0, size: 24, totalElements: 0, totalPages: 0 };
// 계약이 정한 대표 8개 이름·순서와, 대표 밖 고급 목록을 확인할 나머지 항목이다.
const FEATURED_MECHANISM_NAMES = [
  '핸드 관리',
  '주사위 굴림',
  '셋 컬렉션',
  '협력 게임',
  '타일 놓기',
  '조립 보드',
  '솔로/솔로테어 게임',
  '일꾼 놓기'
];
const MECHANISM_OPTIONS = [
  { code: 'HAND_MANAGEMENT', nameKo: '핸드 관리', nameEn: 'Hand Management', featuredOrder: 1, descriptionKo: '손에 든 패를 잘 활용해야 해요' },
  { code: 'DICE_ROLLING', nameKo: '주사위 굴림', nameEn: 'Dice Rolling', featuredOrder: 2, descriptionKo: '주사위를 굴려 결과를 정해요' },
  { code: 'SET_COLLECTION', nameKo: '셋 컬렉션', nameEn: 'Set Collection', featuredOrder: 3, descriptionKo: '같은 종류끼리 모으면 좋아요' },
  { code: 'COOPERATIVE_GAME', nameKo: '협력 게임', nameEn: 'Cooperative Game', featuredOrder: 4, descriptionKo: '모두가 함께 목표를 이루어요' },
  { code: 'TILE_PLACEMENT', nameKo: '타일 놓기', nameEn: 'Tile Placement', featuredOrder: 5, descriptionKo: '타일을 놓아 판을 만들어요' },
  { code: 'MODULAR_BOARD', nameKo: '조립 보드', nameEn: 'Modular Board', featuredOrder: 6, descriptionKo: '할 때마다 판이 다르게 꾸며져요' },
  { code: 'SOLO_GAME', nameKo: '솔로/솔로테어 게임', nameEn: 'Solo / Solitaire Game', featuredOrder: 7, descriptionKo: '혼자서도 즐길 수 있어요' },
  { code: 'WORKER_PLACEMENT', nameKo: '일꾼 놓기', nameEn: 'Worker Placement', featuredOrder: 8, descriptionKo: '자리를 먼저 차지하는 게 중요해요' },
  // 고급 목록 정렬을 확인하려고 응답은 가나다순이 아닌 차례로 둔다.
  { code: 'AREA_MAJORITY', nameKo: '영역 우세', nameEn: 'Area Majority', featuredOrder: null, descriptionKo: '더 많은 영향력을 가진 사람이 이겨요' },
  { code: 'DECK_BUILDING', nameKo: '덱 빌딩', nameEn: 'Deck Building', featuredOrder: null, descriptionKo: '게임 중 내 카드 덱을 더 강하게 만들어요' },
  { code: 'AUCTION', nameKo: '경매', nameEn: 'Auction / Bidding', featuredOrder: null, descriptionKo: '입찰로 원하는 것을 가져가요' }
];

function lastQuery() {
  return getGames.mock.calls.at(-1)[0];
}

async function renderGamesView() {
  const view = render(
    <GamesView title="게임 찾기" gameQuery="" onGameQueryChange={vi.fn()} dataVersion={0} />
  );
  // 메커니즘 선택지는 화면에 들어오면서 한 번 조회한다. 응답 반영까지 기다린다.
  await act(async () => {});
  return view;
}

function openFilterPanel() {
  fireEvent.click(screen.getByRole('button', { name: /게임 필터/ }));
}

// 시트는 결과 보기 CTA로 닫는다.
function closeFilterPanel() {
  fireEvent.click(screen.getByRole('button', { name: /게임 보기$/ }));
}

function mechanismGroup() {
  return screen.getByRole('group', { name: '메커니즘' });
}

function shownMechanismNames() {
  return within(mechanismGroup())
    .getAllByRole('checkbox')
    .map((input) => input.closest('label').textContent.trim());
}

beforeEach(() => {
  vi.useFakeTimers();
  getGames.mockReset();
  getGames.mockResolvedValue(EMPTY_PAGE);
  getGameMechanisms.mockReset();
  getGameMechanisms.mockResolvedValue(MECHANISM_OPTIONS);
  getGameCategories.mockReset();
  getGameCategories.mockResolvedValue([]);
  getGameThemes.mockReset();
  getGameThemes.mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('T2·T3 게임 조건 필터 조회 시점', () => {
  it('숫자 입력은 마지막 입력 0.4초가 지난 뒤에만 조회한다', async () => {
    await renderGamesView();
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

  it('체크박스 선택·해제는 기다리지 않고 바로 조회한다', async () => {
    await renderGamesView();
    openFilterPanel();
    const callsBeforeToggle = getGames.mock.calls.length;

    fireEvent.click(screen.getByLabelText('90분 이상'));

    expect(getGames.mock.calls.length).toBe(callsBeforeToggle + 1);
    expect(lastQuery().playTime).toEqual(['AT_LEAST_90']);
  });

  it('플레이 시간은 확정한 6구간만 제공하고 제거된 구간을 남기지 않는다', async () => {
    await renderGamesView();
    openFilterPanel();

    ['10분 이내', '10~20분', '20~30분', '30~60분', '60~90분', '90분 이상'].forEach((label) => {
      expect(screen.getByLabelText(label)).toBeTruthy();
    });
    ['20분 이하', '20분 초과 60분 이하', '60분 초과'].forEach((label) => {
      expect(screen.queryByLabelText(label)).toBeNull();
    });
  });

  it('플레이 시간 여러 구간을 함께 선택하면 선택한 값을 모두 전달한다', async () => {
    await renderGamesView();
    openFilterPanel();

    fireEvent.click(screen.getByLabelText('10분 이내'));
    fireEvent.click(screen.getByLabelText('90분 이상'));

    expect(lastQuery().playTime).toEqual(['UP_TO_10', 'AT_LEAST_90']);
  });

  it('최연소 참여자 나이는 양의 정수 하나를 보내고 비우면 조건과 칩을 제거한다', async () => {
    await renderGamesView();
    openFilterPanel();

    ['8세 이하', '9~12세', '13~15세', '16세 이상'].forEach((label) => {
      expect(screen.queryByLabelText(label)).toBeNull();
    });
    const input = screen.getByLabelText('최연소 참여자 나이');

    fireEvent.change(input, { target: { value: '0' } });
    expect(input.value).toBe('');

    fireEvent.change(input, { target: { value: '10' } });
    expect(lastQuery().youngestPlayerAge).toBe('10');
    expect(lastQuery()).not.toHaveProperty('ageBand');
    // 걸린 조건 수는 필터 버튼 이름에 남는다.
    expect(screen.getByRole('button', { name: '게임 필터 1' })).toBeTruthy();

    fireEvent.change(input, { target: { value: '' } });
    expect(lastQuery().youngestPlayerAge).toBe('');
    expect(screen.queryByRole('button', { name: '게임 필터 1' })).toBeNull();
  });

  it('필터 영역을 닫았다 다시 열어도 입력과 선택을 유지한다', async () => {
    await renderGamesView();
    openFilterPanel();
    fireEvent.change(screen.getByLabelText('최소'), { target: { value: '2' } });
    fireEvent.click(screen.getByLabelText('90분 이상'));

    closeFilterPanel();
    expect(screen.queryByLabelText('최소')).toBeNull();

    openFilterPanel();

    expect(screen.getByLabelText('최소').value).toBe('2');
    expect(screen.getByLabelText('90분 이상').checked).toBe(true);
  });
});

describe('T1 인원 범위와 전용 인원의 화면 전환', () => {
  it('전용 인원을 하나 고르면 그 인원의 범위와 정확히 일치를 화면에 되비춘다', async () => {
    await renderGamesView();
    openFilterPanel();

    fireEvent.click(screen.getByLabelText('1인 전용'));

    expect(screen.getByLabelText('최소').value).toBe('1');
    expect(screen.getByLabelText('최대').value).toBe('1');
    expect(screen.getByLabelText('인원 정확히 일치').checked).toBe(true);
  });

  it('되비춘 범위는 요청에 담지 않고 전용 인원 조건만 보낸다', async () => {
    await renderGamesView();
    openFilterPanel();

    fireEvent.click(screen.getByLabelText('2인 전용'));
    act(() => { vi.advanceTimersByTime(400); });

    expect(lastQuery().exclusivePlayerCount).toEqual(['2']);
    expect(lastQuery().playerCountMin).toBe('');
    expect(lastQuery().playerCountMax).toBe('');
    expect(lastQuery().playerCountExact).toBe(false);
  });

  it('전용 인원을 둘 다 고르면 범위 입력을 비우고 두 값을 함께 보낸다', async () => {
    await renderGamesView();
    openFilterPanel();

    fireEvent.click(screen.getByLabelText('1인 전용'));
    fireEvent.click(screen.getByLabelText('2인 전용'));
    act(() => { vi.advanceTimersByTime(400); });

    expect(screen.getByLabelText('최소').value).toBe('');
    expect(screen.getByLabelText('최대').value).toBe('');
    expect(screen.getByLabelText('인원 정확히 일치').checked).toBe(false);
    expect(lastQuery().exclusivePlayerCount).toEqual(['1', '2']);
  });

  it('전용 인원 하나를 해제하면 남은 하나를 다시 범위에 되비춘다', async () => {
    await renderGamesView();
    openFilterPanel();
    fireEvent.click(screen.getByLabelText('1인 전용'));
    fireEvent.click(screen.getByLabelText('2인 전용'));

    fireEvent.click(screen.getByLabelText('1인 전용'));

    expect(screen.getByLabelText('최소').value).toBe('2');
    expect(screen.getByLabelText('최대').value).toBe('2');
    expect(screen.getByLabelText('인원 정확히 일치').checked).toBe(true);
  });

  it('전용 인원으로 전환할 때 범위와 전용 인원을 함께 담은 요청을 보내지 않는다', async () => {
    await renderGamesView();
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

  it('인원 범위를 입력하면 선택한 전용 인원을 해제한다', async () => {
    await renderGamesView();
    openFilterPanel();
    fireEvent.click(screen.getByLabelText('2인 전용'));

    fireEvent.change(screen.getByLabelText('최소'), { target: { value: '3' } });
    act(() => { vi.advanceTimersByTime(400); });

    expect(screen.getByLabelText('2인 전용').checked).toBe(false);
    expect(lastQuery().exclusivePlayerCount).toEqual([]);
    expect(lastQuery().playerCountMin).toBe('3');
  });

  it('전용 인원에서 범위로 전환하면 되비추던 최대와 정확히 일치를 남기지 않는다', async () => {
    await renderGamesView();
    openFilterPanel();
    fireEvent.click(screen.getByLabelText('2인 전용'));

    fireEvent.change(screen.getByLabelText('최소'), { target: { value: '3' } });
    act(() => { vi.advanceTimersByTime(400); });

    expect(screen.getByLabelText('최대').value).toBe('');
    expect(screen.getByLabelText('인원 정확히 일치').checked).toBe(false);
    expect(lastQuery().playerCountMax).toBe('');
    expect(lastQuery().playerCountExact).toBe(false);
  });

  it('전용 인원에서 정확히 일치만 해제해도 되비추던 범위를 남기지 않는다', async () => {
    await renderGamesView();
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

describe('T4 대표 메커니즘과 설명', () => {
  it('대표 8개를 계약이 정한 이름과 순서로 노출한다', async () => {
    await renderGamesView();
    openFilterPanel();

    expect(shownMechanismNames()).toEqual(FEATURED_MECHANISM_NAMES);
  });

  it('대표 항목의 정보 아이콘을 누르면 그 항목의 설명을 말풍선으로 연다', async () => {
    await renderGamesView();
    openFilterPanel();

    const hint = screen.getByLabelText('핸드 관리 설명');
    expect(hint.getAttribute('aria-expanded')).toBe('false');

    fireEvent.click(hint);

    expect(hint.getAttribute('aria-expanded')).toBe('true');
    const tooltip = document.getElementById(hint.getAttribute('aria-describedby'));
    expect(tooltip.getAttribute('role')).toBe('tooltip');
    expect(tooltip.textContent.trim()).not.toBe('');
    // 말풍선은 화면을 막지 않으므로 다른 조건을 보면서 설명을 읽을 수 있다.
    expect(tooltip.getAttribute('aria-modal')).toBeNull();
    expect(screen.getByLabelText('90분 이상')).toBeTruthy();
  });

  it('아이콘을 다시 누르면 말풍선을 닫는다', async () => {
    await renderGamesView();
    openFilterPanel();
    const hint = screen.getByLabelText('핸드 관리 설명');

    fireEvent.click(hint);
    fireEvent.click(hint);

    expect(hint.getAttribute('aria-expanded')).toBe('false');
  });

  it('마우스가 올라오거나 focus를 받은 상태에서 눌러도 말풍선이 닫히지 않는다', async () => {
    await renderGamesView();
    openFilterPanel();
    const hint = screen.getByLabelText('셋 컬렉션 설명');

    // tap은 hover·focus를 함께 일으킨다. 이 순서에서 눌러도 설명이 열려 있어야 한다.
    fireEvent.mouseEnter(hint.parentElement);
    fireEvent.focus(hint);
    fireEvent.click(hint);

    expect(hint.getAttribute('aria-expanded')).toBe('true');
  });

  it('터치로 같은 아이콘을 다시 눌러도 말풍선을 닫는다', async () => {
    await renderGamesView();
    openFilterPanel();
    const hint = screen.getByLabelText('셋 컬렉션 설명');

    // tap이 남긴 hover 상태가 두 번째 tap 이후에도 남아 있으면 안 된다.
    fireEvent.mouseEnter(hint.parentElement);
    fireEvent.focus(hint);
    fireEvent.click(hint);
    fireEvent.click(hint);

    expect(hint.getAttribute('aria-expanded')).toBe('false');
    const tooltip = document.getElementById(hint.getAttribute('aria-describedby'));
    expect(tooltip.style.visibility).toBe('hidden');
  });

  it('고정된 말풍선 바깥을 누르면 닫혀 그 아래 조건을 다시 누를 수 있다', async () => {
    await renderGamesView();
    openFilterPanel();
    const hint = screen.getByLabelText('셋 컬렉션 설명');

    fireEvent.click(hint);
    expect(hint.getAttribute('aria-expanded')).toBe('true');

    fireEvent.pointerDown(document.body);

    expect(hint.getAttribute('aria-expanded')).toBe('false');
  });

  it('고정된 말풍선은 Escape로 닫는다', async () => {
    await renderGamesView();
    openFilterPanel();
    const hint = screen.getByLabelText('셋 컬렉션 설명');

    fireEvent.click(hint);
    expect(hint.getAttribute('aria-expanded')).toBe('true');

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(hint.getAttribute('aria-expanded')).toBe('false');
  });

  it('focus로 열린 말풍선은 Escape로 닫는다', async () => {
    await renderGamesView();
    openFilterPanel();
    const hint = screen.getByLabelText('셋 컬렉션 설명');
    const tooltip = document.getElementById(hint.getAttribute('aria-describedby'));

    fireEvent.focus(hint);
    expect(tooltip.style.visibility).toBe('visible');

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(tooltip.style.visibility).toBe('hidden');
  });

  it('넘치지 않는 고정 말풍선은 겹친 자리를 눌러도 닫혀 아래 컨트롤이 클릭을 받을 수 있다', async () => {
    await renderGamesView();
    openFilterPanel();
    const hint = screen.getByLabelText('셋 컬렉션 설명');

    fireEvent.click(hint);
    expect(hint.getAttribute('aria-expanded')).toBe('true');

    const tooltip = document.getElementById(hint.getAttribute('aria-describedby'));
    fireEvent.pointerDown(tooltip);

    expect(hint.getAttribute('aria-expanded')).toBe('false');
  });

  it('내용이 넘쳐 스크롤이 필요한 고정 말풍선은 그 위를 눌러도 닫히지 않는다', async () => {
    await renderGamesView();
    openFilterPanel();
    const hint = screen.getByLabelText('셋 컬렉션 설명');
    const tooltip = document.getElementById(hint.getAttribute('aria-describedby'));

    // jsdom은 레이아웃을 계산하지 않으므로 스크롤이 필요한 상태를 직접 만든다.
    Object.defineProperty(tooltip, 'scrollHeight', { configurable: true, value: 400 });
    Object.defineProperty(tooltip, 'clientHeight', { configurable: true, value: 200 });

    fireEvent.click(hint);
    expect(hint.getAttribute('aria-expanded')).toBe('true');
    expect(tooltip.style.pointerEvents).toBe('auto');

    fireEvent.pointerDown(tooltip);

    expect(hint.getAttribute('aria-expanded')).toBe('true');
  });

  it('hover로 연 스크롤형 말풍선은 포인터를 옮겨도 닫히지 않는다', async () => {
    await renderGamesView();
    openFilterPanel();
    const hint = screen.getByLabelText('셋 컬렉션 설명');
    const trigger = hint.parentElement;
    const tooltip = document.getElementById(hint.getAttribute('aria-describedby'));

    Object.defineProperty(tooltip, 'scrollHeight', { configurable: true, value: 400 });
    Object.defineProperty(tooltip, 'clientHeight', { configurable: true, value: 200 });

    fireEvent.mouseEnter(trigger);
    expect(tooltip.style.visibility).toBe('visible');
    expect(tooltip.style.pointerEvents).toBe('auto');

    fireEvent.mouseLeave(trigger);
    expect(tooltip.style.visibility).toBe('visible');
    fireEvent.mouseEnter(tooltip);
    act(() => { vi.advanceTimersByTime(100); });

    expect(tooltip.style.visibility).toBe('visible');
  });

  it('대표 8개 모두 API가 준 서로 다른 설명을 연결한다', async () => {
    await renderGamesView();
    openFilterPanel();

    const descriptions = FEATURED_MECHANISM_NAMES.map((name) => {
      const hint = screen.getByLabelText(name + ' 설명');
      return document.getElementById(hint.getAttribute('aria-describedby')).textContent.trim();
    });

    expect(descriptions.every(Boolean)).toBe(true);
    expect(new Set(descriptions).size).toBe(FEATURED_MECHANISM_NAMES.length);
  });

  it('대표 항목은 화면 상수가 아닌 API 설명을 표시한다', async () => {
    await renderGamesView();
    openFilterPanel();

    const expectedByName = {
      '핸드 관리': '손에 든 패를 잘 활용해야 해요',
      '주사위 굴림': '주사위를 굴려 결과를 정해요',
      '셋 컬렉션': '같은 종류끼리 모으면 좋아요',
      '협력 게임': '모두가 함께 목표를 이루어요',
      '타일 놓기': '타일을 놓아 판을 만들어요',
      '조립 보드': '할 때마다 판이 다르게 꾸며져요',
      '솔로/솔로테어 게임': '혼자서도 즐길 수 있어요',
      '일꾼 놓기': '자리를 먼저 차지하는 게 중요해요'
    };

    Object.entries(expectedByName).forEach(([name, text]) => {
      const hint = screen.getByLabelText(name + ' 설명');
      const tooltip = document.getElementById(hint.getAttribute('aria-describedby'));
      expect(tooltip.textContent.trim()).toBe(text);
    });
  });

  it('고급 목록의 항목도 API 설명을 키보드로 확인할 수 있다', async () => {
    await renderGamesView();
    openFilterPanel();
    fireEvent.click(screen.getByRole('button', { name: '메커니즘 더 보기' }));

    const hint = screen.getByLabelText('경매 설명');
    fireEvent.focus(hint);
    expect(document.getElementById(hint.getAttribute('aria-describedby')).textContent.trim()).toBe('입찰로 원하는 것을 가져가요');
  });

  it('고급 목록 툴팁은 스크롤 영역 밖의 fixed portal로 렌더링한다', async () => {
    await renderGamesView();
    openFilterPanel();
    fireEvent.click(screen.getByRole('button', { name: '메커니즘 더 보기' }));

    const hint = screen.getByLabelText('경매 설명');
    fireEvent.click(hint);

    const tooltip = document.getElementById(hint.getAttribute('aria-describedby'));
    expect(tooltip.parentElement).toBe(document.body);
    expect(tooltip.style.position).toBe('fixed');
    expect(tooltip.style.visibility).toBe('visible');
    expect(document.querySelector('.mechanism-advanced-list [role="tooltip"]')).toBeNull();
  });

  it('말풍선은 짧은 viewport에서도 세로로 잘리지 않도록 내부 스크롤을 허용한다', () => {
    const stylesPath = join(dirname(fileURLToPath(import.meta.url)), 'styles.css');
    const stylesCss = readFileSync(stylesPath, 'utf-8');
    const rule = stylesCss.match(/\.mechanism-hint-text\s*\{[^}]*\}/)[0];

    expect(rule).toMatch(/max-height:\s*calc\(100dvh - 16px\)/);
    expect(rule).toMatch(/overflow-y:\s*auto/);
  });

  it('설명이 없거나 공백인 선택지에는 빈 툴팁을 렌더링하지 않는다', async () => {
    getGameMechanisms.mockResolvedValueOnce(MECHANISM_OPTIONS.map((option) => (
      option.code === 'DICE_ROLLING' ? { ...option, descriptionKo: '  ' } : option
    )));
    await renderGamesView();
    openFilterPanel();

    expect(screen.getByLabelText('주사위 굴림')).toBeTruthy();
    expect(screen.queryByLabelText('주사위 굴림 설명')).toBeNull();
  });
});

describe('T5 메커니즘 고급 목록', () => {
  it('고급 목록은 기본으로 접혀 있어 대표 8개 밖 항목을 노출하지 않는다', async () => {
    await renderGamesView();
    openFilterPanel();

    expect(screen.getByRole('button', { name: '메커니즘 더 보기' }).getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByLabelText('경매')).toBeNull();
    expect(screen.queryByLabelText('덱 빌딩')).toBeNull();
  });

  it('고급 목록을 열면 대표 8개 밖 항목을 한국어 표시명 가나다순으로 제공한다', async () => {
    await renderGamesView();
    openFilterPanel();

    fireEvent.click(screen.getByRole('button', { name: '메커니즘 더 보기' }));

    expect(shownMechanismNames()).toEqual([...FEATURED_MECHANISM_NAMES, '경매', '덱 빌딩', '영역 우세']);
  });

  it('검색은 한국어명과 BGG 영문명 모두 일치시키고 화면에는 한국어명만 표시한다', async () => {
    await renderGamesView();
    openFilterPanel();
    fireEvent.click(screen.getByRole('button', { name: '메커니즘 더 보기' }));
    const search = screen.getByLabelText('메커니즘 검색');

    fireEvent.change(search, { target: { value: '덱' } });
    expect(screen.getByLabelText('덱 빌딩')).toBeTruthy();
    expect(screen.queryByLabelText('경매')).toBeNull();

    fireEvent.change(search, { target: { value: 'bidding' } });
    expect(screen.getByLabelText('경매')).toBeTruthy();
    expect(screen.queryByLabelText('덱 빌딩')).toBeNull();
    expect(screen.queryByText(/Auction/)).toBeNull();
  });
});

// 대표 10개·고급 목록 정렬을 함께 확인하려고 API 응답 순서는 가나다순이 아니게 둔다.
const THEME_OPTIONS = [
  { code: 'ZOMBIES', nameKo: '좀비', nameEn: 'Zombies' },
  { code: 'FANTASY', nameKo: '판타지', nameEn: 'Fantasy' },
  { code: 'HORROR', nameKo: '공포', nameEn: 'Horror' },
  { code: 'ANCIENT', nameKo: '고대', nameEn: 'Ancient' },
  { code: 'ECONOMIC', nameKo: '경제', nameEn: 'Economic' },
  { code: 'MYTHOLOGY', nameKo: '신화', nameEn: 'Mythology' },
  { code: 'TRAVEL', nameKo: '여행', nameEn: 'Travel' },
  { code: 'HUMOR', nameKo: '유머', nameEn: 'Humor' },
  { code: 'MUSIC', nameKo: '음악', nameEn: 'Music' },
  { code: 'MEDIEVAL', nameKo: '중세', nameEn: 'Medieval' },
  // 가나다순 11번째 이후만 고급 목록에 남는다.
  { code: 'PIRATES', nameKo: '해적', nameEn: 'Pirates' },
  { code: 'RACING', nameKo: '레이싱', nameEn: 'Racing' }
];
// 가나다순 대표 10개: 경제 고대 공포 레이싱 신화 여행 유머 음악 좀비 중세 (판타지·해적은 고급 목록)
const FEATURED_THEME_NAMES = ['경제', '고대', '공포', '레이싱', '신화', '여행', '유머', '음악', '좀비', '중세'];

function themeGroup() {
  return screen.getByRole('group', { name: '테마' });
}

function shownThemeNames() {
  return within(themeGroup())
    .getAllByRole('checkbox')
    .map((input) => input.closest('label').textContent.trim());
}

describe('T7 테마 대표·고급 목록', () => {
  beforeEach(() => {
    getGameThemes.mockResolvedValue(THEME_OPTIONS);
  });

  it('가나다순 대표 10개만 상시 노출하고 나머지는 접어 둔다', async () => {
    await renderGamesView();
    openFilterPanel();

    expect(shownThemeNames()).toEqual(FEATURED_THEME_NAMES);
    expect(screen.getByRole('button', { name: '테마 더 보기' }).getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByLabelText('해적')).toBeNull();
    expect(screen.queryByLabelText('판타지')).toBeNull();
  });

  it('테마 더 보기를 열면 대표 10개 밖 항목을 가나다순으로 이어 보여 준다', async () => {
    await renderGamesView();
    openFilterPanel();

    fireEvent.click(screen.getByRole('button', { name: '테마 더 보기' }));

    expect(shownThemeNames()).toEqual([...FEATURED_THEME_NAMES, '판타지', '해적']);
  });

  it('고급 목록 검색은 이름 일부로 걸러낸다', async () => {
    await renderGamesView();
    openFilterPanel();
    fireEvent.click(screen.getByRole('button', { name: '테마 더 보기' }));

    fireEvent.change(screen.getByLabelText('테마 검색'), { target: { value: '레이' } });

    expect(screen.getByLabelText('레이싱')).toBeTruthy();
    expect(screen.queryByLabelText('해적')).toBeNull();
  });

  it('테마 목록 닫기를 누르면 목록을 접고 더 보기 버튼으로 포커스를 되돌린다', async () => {
    await renderGamesView();
    openFilterPanel();
    fireEvent.click(screen.getByRole('button', { name: '테마 더 보기' }));

    fireEvent.click(screen.getByText('테마 목록 닫기'));

    const moreButton = screen.getByRole('button', { name: '테마 더 보기' });
    expect(moreButton.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(moreButton);
  });
});

describe('T8 테마 선택과 조회', () => {
  beforeEach(() => {
    getGameThemes.mockResolvedValue(THEME_OPTIONS);
  });

  it('대표 목록에서 선택하면 기다리지 않고 반복 theme 파라미터로 조회한다', async () => {
    await renderGamesView();
    openFilterPanel();
    const callsBeforeToggle = getGames.mock.calls.length;

    fireEvent.click(screen.getByLabelText('공포'));
    expect(getGames.mock.calls.length).toBe(callsBeforeToggle + 1);
    expect(lastQuery().theme).toEqual(['HORROR']);

    fireEvent.click(screen.getByLabelText('경제'));
    expect(lastQuery().theme).toEqual(['HORROR', 'ECONOMIC']);
  });

  it('고급 목록에서 검색해 고른 항목도 조회 조건에 반영한다', async () => {
    await renderGamesView();
    openFilterPanel();
    fireEvent.click(screen.getByRole('button', { name: '테마 더 보기' }));
    fireEvent.change(screen.getByLabelText('테마 검색'), { target: { value: '해적' } });

    fireEvent.click(screen.getByLabelText('해적'));

    expect(lastQuery().theme).toEqual(['PIRATES']);
  });

  it('테마 포함 방식 스위치는 항상 보이고 ALL 선택이 조회에 반영된다', async () => {
    await renderGamesView();
    openFilterPanel();
    const matchSwitch = within(themeGroup()).getByRole('switch', { name: '테마 포함 방식' });
    expect(matchSwitch.getAttribute('aria-checked')).toBe('true');
    expect(matchSwitch.getAttribute('aria-describedby')).toBe('테마-match-description');

    fireEvent.click(screen.getByLabelText('공포'));
    fireEvent.click(screen.getByLabelText('경제'));
    fireEvent.click(matchSwitch);

    expect(lastQuery().theme).toEqual(['HORROR', 'ECONOMIC']);
    expect(lastQuery().themeMatch).toBe('ALL');
    expect(matchSwitch.getAttribute('aria-checked')).toBe('false');
    expect(matchSwitch.getAttribute('aria-label')).toBe('테마 포함 방식');
    expect(document.getElementById('테마-match-description').textContent).toBe('켜짐은 하나라도 포함, 꺼짐은 모두 포함');
  });

  it('테마를 선택하지 않거나 하나만 골라도 포함 방식 스위치를 보여 준다', async () => {
    await renderGamesView();
    openFilterPanel();
    expect(within(themeGroup()).getByRole('switch', { name: '테마 포함 방식' })).toBeTruthy();

    fireEvent.click(screen.getByLabelText('공포'));

    expect(within(themeGroup()).getByRole('switch', { name: '테마 포함 방식' })).toBeTruthy();
  });
});

describe('T6 메커니즘 선택과 조회', () => {
  it('테마와 메커니즘 포함 방식을 항상 표시하고 서로 독립적으로 바꾼다', async () => {
    await renderGamesView();
    openFilterPanel();
    const themeMatch = within(themeGroup()).getByRole('switch', { name: '테마 포함 방식' });
    const mechanismMatch = within(mechanismGroup()).getByRole('switch', { name: '메커니즘 포함 방식' });

    expect(themeMatch.getAttribute('aria-checked')).toBe('true');
    expect(mechanismMatch.getAttribute('aria-checked')).toBe('true');

    fireEvent.click(themeMatch);
    expect(lastQuery().themeMatch).toBe('ALL');
    expect(lastQuery().mechanismMatch).toBe('');

    fireEvent.click(mechanismMatch);
    expect(lastQuery().themeMatch).toBe('ALL');
    expect(lastQuery().mechanismMatch).toBe('ALL');
  });

  it('마지막 선택을 해제하면 해당 그룹만 ANY로 초기화한다', async () => {
    getGameThemes.mockResolvedValue(THEME_OPTIONS);
    await renderGamesView();
    openFilterPanel();
    const themeMatch = within(themeGroup()).getByRole('switch', { name: '테마 포함 방식' });
    const mechanismMatch = within(mechanismGroup()).getByRole('switch', { name: '메커니즘 포함 방식' });

    fireEvent.click(screen.getByLabelText('공포'));
    fireEvent.click(screen.getByLabelText('핸드 관리'));
    fireEvent.click(themeMatch);
    fireEvent.click(mechanismMatch);
    fireEvent.click(screen.getByLabelText('공포'));

    expect(lastQuery().theme).toEqual([]);
    expect(lastQuery().themeMatch).toBe('');
    expect(lastQuery().mechanismMatch).toBe('ALL');
    expect(themeMatch.getAttribute('aria-checked')).toBe('true');

    fireEvent.click(screen.getByLabelText('핸드 관리'));
    expect(lastQuery().mechanism).toEqual([]);
    expect(lastQuery().mechanismMatch).toBe('');
    expect(mechanismMatch.getAttribute('aria-checked')).toBe('true');
  });

  it('선택·해제하면 기다리지 않고 반복 mechanism 파라미터로 조회한다', async () => {
    await renderGamesView();
    openFilterPanel();
    const callsBeforeToggle = getGames.mock.calls.length;

    fireEvent.click(screen.getByLabelText('핸드 관리'));
    expect(getGames.mock.calls.length).toBe(callsBeforeToggle + 1);
    expect(lastQuery().mechanism).toEqual(['HAND_MANAGEMENT']);

    fireEvent.click(screen.getByLabelText('일꾼 놓기'));
    expect(lastQuery().mechanism).toEqual(['HAND_MANAGEMENT', 'WORKER_PLACEMENT']);

    fireEvent.click(screen.getByLabelText('핸드 관리'));
    expect(lastQuery().mechanism).toEqual(['WORKER_PLACEMENT']);
  });

  it('고급 목록을 닫았다 다시 열어도 선택 상태를 유지한다', async () => {
    await renderGamesView();
    openFilterPanel();
    fireEvent.click(screen.getByRole('button', { name: '메커니즘 더 보기' }));
    fireEvent.click(screen.getByLabelText('덱 빌딩'));

    fireEvent.click(screen.getByRole('button', { name: '메커니즘 더 보기' }));
    expect(screen.queryByLabelText('덱 빌딩')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '메커니즘 더 보기' }));

    expect(screen.getByLabelText('덱 빌딩').checked).toBe(true);
    expect(lastQuery().mechanism).toEqual(['DECK_BUILDING']);
  });

  it('선택지 조회는 화면에 들어올 때 한 번만 수행한다', async () => {
    await renderGamesView();
    openFilterPanel();

    fireEvent.click(screen.getByLabelText('핸드 관리'));
    closeFilterPanel();
    openFilterPanel();

    expect(getGameMechanisms).toHaveBeenCalledTimes(1);
  });
});

describe('T10 조건 조합', () => {
  function applyEveryCondition() {
    fireEvent.change(screen.getByLabelText('최소'), { target: { value: '2' } });
    fireEvent.change(screen.getByLabelText('최대'), { target: { value: '4' } });
    fireEvent.click(screen.getByLabelText('90분 이상'));
    fireEvent.click(screen.getByLabelText('핸드 관리'));
    // 해 본 게임 조건은 칩 줄에서 고른다.
    fireEvent.click(screen.getByRole('button', { name: '해 본 게임만' }));
    fireEvent.click(within(screen.getByRole('group', { name: '게임 난이도' })).getByLabelText('3점대'));
    fireEvent.click(screen.getByLabelText('예정 모임 있는 게임만'));
    act(() => { vi.advanceTimersByTime(400); });
  }

  it('모든 조건과 검색어를 함께 걸어도 계약한 파라미터를 한 요청에 담는다', async () => {
    render(<GamesView title="게임 찾기" gameQuery="루미" onGameQueryChange={vi.fn()} dataVersion={0} />);
    await act(async () => {});
    openFilterPanel();

    applyEveryCondition();

    expect(lastQuery()).toMatchObject({
      keyword: '루미',
      playerCountMin: '2',
      playerCountMax: '4',
      playerCountExact: false,
      exclusivePlayerCount: [],
      playTime: ['AT_LEAST_90'],
      mechanism: ['HAND_MANAGEMENT'],
      playedFilter: 'PLAYED_ONLY',
      complexityMin: '3',
      complexityMax: '3.99',
      upcomingOnly: true
    });
  });

  it('건 조건을 모두 칩으로 보여 주고 초기화하면 함께 해제한다', async () => {
    await renderGamesView();
    openFilterPanel();
    applyEveryCondition();

    expect(screen.getByRole('button', { name: '게임 필터 6' })).toBeTruthy();

    fireEvent.click(screen.getByText('초기화'));
    act(() => { vi.advanceTimersByTime(400); });

    expect(screen.queryByRole('button', { name: /게임 필터 \d/ })).toBeNull();
    expect(lastQuery()).toMatchObject({
      playerCountMin: '',
      playTime: [],
      mechanism: [],
      playedFilter: '',
      upcomingOnly: false
    });
  });

  it('조건을 걸어 둔 채 필터를 닫았다 열어도 모든 선택 상태를 유지한다', async () => {
    await renderGamesView();
    openFilterPanel();
    applyEveryCondition();

    closeFilterPanel();
    openFilterPanel();

    expect(screen.getByLabelText('최소').value).toBe('2');
    expect(screen.getByLabelText('90분 이상').checked).toBe(true);
    expect(screen.getByLabelText('핸드 관리').checked).toBe(true);
    expect(screen.getByRole('button', { name: '해 본 게임만' }).getAttribute('aria-pressed')).toBe('true');
  });

  it('조건을 건 조회의 로딩과 빈 결과를 그대로 알린다', async () => {
    let resolvePage;
    getGames.mockReturnValue(new Promise((resolve) => { resolvePage = resolve; }));
    await renderGamesView();

    // 목록 자리에 불러오는 중임을 알린다.
    expect(screen.getByText('불러오는 중')).toBeTruthy();

    await act(async () => { resolvePage(EMPTY_PAGE); });
    expect(screen.getByText(/검색 결과가 없어요/)).toBeTruthy();
  });

  it('조건을 건 조회가 실패하면 오류를 알린다', async () => {
    getGames.mockRejectedValue(new Error('조회하지 못했어요.'));

    await renderGamesView();

    expect(screen.getByText('요청을 처리하지 못했어요.')).toBeTruthy();
  });
});

describe('필터 시트 키보드 조작', () => {
  it('시트를 열면 포커스를 안에 가두고 닫을 때 필터 버튼으로 되돌린다', async () => {
    await renderGamesView();
    const toggle = screen.getByRole('button', { name: /게임 필터/ });
    openFilterPanel();

    const sheet = screen.getByRole('dialog');
    expect(sheet.contains(document.activeElement)).toBe(true);

    // 마지막 조작에서 Tab을 누르면 뒤쪽 칩 줄이 아니라 시트의 처음으로 돌아온다.
    const focusables = [...sheet.querySelectorAll('a[href], button, input, select, textarea')].filter((node) => !node.disabled);
    const last = focusables[focusables.length - 1];
    last.focus();
    fireEvent.keyDown(window, { key: 'Tab' });
    expect(document.activeElement).toBe(focusables[0]);

    // 첫 조작에서 Shift+Tab은 마지막으로 돈다.
    fireEvent.keyDown(window, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(last);

    closeFilterPanel();
    expect(document.activeElement).toBe(toggle);
  });
});
