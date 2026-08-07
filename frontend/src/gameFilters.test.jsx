import React from 'react';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const getGames = vi.fn();
const getGameMechanisms = vi.fn();

vi.mock('./api', () => ({
  ApiError: class ApiError extends Error {},
  api: {
    getGames: (...parameters) => getGames(...parameters),
    getGameMechanisms: (...parameters) => getGameMechanisms(...parameters),
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
  fireEvent.click(screen.getByLabelText('조건 필터'));
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

  it('필터 영역을 닫았다 다시 열어도 입력과 선택을 유지한다', async () => {
    await renderGamesView();
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
    // 화면을 막는 모달을 쓰지 않으므로 다른 조건을 보면서 설명을 읽을 수 있다.
    expect(screen.queryByRole('dialog')).toBeNull();
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

describe('T6 메커니즘 선택과 조회', () => {
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
    fireEvent.click(screen.getByText('닫기'));
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
    fireEvent.click(within(screen.getByRole('group', { name: '해 본 게임' })).getByLabelText('해 본 게임만'));
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

    ['2~4명', '90분 이상', '핸드 관리', '해 본 게임만', '난이도 3점대', '예정 모임 있음'].forEach((label) => {
      expect(screen.getByLabelText(label + ' 조건 해제')).toBeTruthy();
    });

    fireEvent.click(screen.getByText('초기화'));
    act(() => { vi.advanceTimersByTime(400); });

    expect(screen.queryByLabelText('핸드 관리 조건 해제')).toBeNull();
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

    fireEvent.click(screen.getByText('닫기'));
    openFilterPanel();

    expect(screen.getByLabelText('최소').value).toBe('2');
    expect(screen.getByLabelText('90분 이상').checked).toBe(true);
    expect(screen.getByLabelText('핸드 관리').checked).toBe(true);
    expect(within(screen.getByRole('group', { name: '해 본 게임' })).getByLabelText('해 본 게임만').checked).toBe(true);
  });

  it('조건을 건 조회의 로딩과 빈 결과를 그대로 알린다', async () => {
    let resolvePage;
    getGames.mockReturnValue(new Promise((resolve) => { resolvePage = resolve; }));
    await renderGamesView();

    // 제목 옆 건수와 목록 자리 모두 불러오는 중임을 알린다.
    expect(screen.getAllByText('불러오는 중…')).toHaveLength(2);

    await act(async () => { resolvePage(EMPTY_PAGE); });
    expect(screen.getByText(/검색 결과가 없어요/)).toBeTruthy();
  });

  it('조건을 건 조회가 실패하면 오류를 알린다', async () => {
    getGames.mockRejectedValue(new Error('조회하지 못했어요.'));

    await renderGamesView();

    expect(screen.getByText('요청을 처리하지 못했어요.')).toBeTruthy();
  });
});
