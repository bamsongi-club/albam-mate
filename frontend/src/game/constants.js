import defaultGameCover from '../../assets/default-game-cover.png';

export const GAME_SEARCH_PAGE_SIZE = 10;
export const GAME_LIST_PAGE_SIZE = 24;
export const ROOM_LIST_PAGE_SIZE = 12;
export const GAME_SEARCH_DEBOUNCE_MS = 250;
// 인원 숫자 입력은 마지막 입력 뒤 이 시간이 지나면 조회한다. 체크박스는 기다리지 않는다.
export const GAME_NUMBER_FILTER_DEBOUNCE_MS = 400;
export const DEFAULT_GAME_COVER_URL = defaultGameCover;


// 게임 필터 상태는 쿼리 파라미터 이름과 값을 그대로 쓴다. 빈 문자열과 빈 배열은 조건 없음이라 요청에서 빠진다.
export const EMPTY_GAME_FILTERS = {
  playerCountMin: '',
  playerCountMax: '',
  playerCountExact: false,
  exclusivePlayerCount: [],
  playTime: [],
  youngestPlayerAge: '',
  complexityMin: '',
  complexityMax: '',
  mechanism: [],
  mechanismMatch: '',
  category: [],
  theme: [],
  // 포함 방식의 빈 값은 API 기본값 ANY이며 요청에서 빠진다.
  themeMatch: '',
  recommendedPlayerCount: [],
  bestPlayerCount: [],
  // 관계 필터는 한 값만 쓴다. 빈 값이 `전체`이며 요청에서 빠진다.
  playedFilter: '',
  upcomingOnly: false
};

// 계약은 추천·베스트 인원 상한을 두지 않으므로 자주 쓰는 1~6명만 체크박스로 먼저 보이고,
// 그보다 큰 값은 CustomPlayerCountInput으로 직접 입력해 추가한다.
export const PREFERRED_PLAYER_COUNT_OPTIONS = Array.from({ length: 6 }, (_, index) => ({
  value: String(index + 1),
  label: index + 1 + '명'
}));

export const EMPTY_GAME_FILTER_KEY = JSON.stringify(EMPTY_GAME_FILTERS);
export const EMPTY_PLAYER_COUNT_RANGE = { playerCountMin: '', playerCountMax: '', playerCountExact: false };

// 계약의 허용값은 1인과 2인뿐이다.
export const EXCLUSIVE_PLAYER_COUNT_OPTIONS = [
  { value: '1', label: '1인 전용' },
  { value: '2', label: '2인 전용' }
];

// 관계 필터는 게임 난이도와 같은 단일 선택이다. 둘 이상의 관계 조건을 만들지 않는다.
export const PLAYED_FILTER_OPTIONS = [
  { value: '', label: '전체' },
  { value: 'PLAYED_ONLY', label: '해 본 게임만' },
  { value: 'EXCLUDE_PLAYED', label: '해 본 게임 제외' }
];

export const PLAY_TIME_LABEL = {
  UP_TO_10: '10분 이내',
  OVER_10_TO_20: '10~20분',
  OVER_20_TO_30: '20~30분',
  OVER_30_TO_60: '30~60분',
  OVER_60_UNDER_90: '60~90분',
  AT_LEAST_90: '90분 이상'
};

// 난이도 점대는 계약의 닫힌 구간 하한·상한으로 보낸다. 5점만 있는 마지막 칸은 상한도 5다.
export const COMPLEXITY_BANDS = [
  { value: '1', label: '1점대', min: '1', max: '1.99' },
  { value: '2', label: '2점대', min: '2', max: '2.99' },
  { value: '3', label: '3점대', min: '3', max: '3.99' },
  { value: '4', label: '4점대', min: '4', max: '4.99' },
  { value: '5', label: '5점', min: '5', max: '5' }
];
