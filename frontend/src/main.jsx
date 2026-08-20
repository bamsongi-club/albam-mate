import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import poweredByBgg from '../assets/powered-by-bgg.svg';
import { ApiError, api, clearCsrfToken, messageForError, setUnauthenticatedHandler, socialLoginUrl } from './api';
import { isUnauthenticated, useCumulativeRequest, usePaginatedRequest, useRequest } from './shared/async';
import { FilterCheckGroup, FilterPanel, FilterRadioGroup } from './shared/filters';
import {
  ArrowIcon,
  Avatar,
  BackIcon,
  BellIcon,
  BggAttribution,
  BrandMark,
  CameraIcon,
  ChatIcon,
  CloseIcon,
  Cover,
  EditIcon,
  ErrorBox,
  EyeIcon,
  EyeOffIcon,
  InfoIcon,
  Meeples,
  Pagination,
  PlusIcon,
  RoomSkeletons,
  ScreenTitle,
  SearchIcon,
  SeatCount,
  SendIcon,
  StateBlock,
  TopBar
} from './shared/ui';
import { nameColor, playerColor } from './shared/players';
import { GameDetailView, GamePickerDialog, GameRankingView, GamesView, EMPTY_GAME_FILTERS, ROOM_LIST_PAGE_SIZE, normalizeRoom } from './game';
import { MobileHomePanel } from './mobile/MobileHomePanel';
import { NotificationPanel } from './notification/NotificationPanel';
import { selectNotificationAndNavigate } from './notification/notificationNavigation';
import { NOTIFICATION_POLL_INTERVAL_MS, useNotificationPolling } from './notification/useNotificationPolling';
import { useNotificationReadSync } from './notification/useNotificationReadSync';
import { MobileBottomNavigation, ROOT_ROUTES } from './mobile/MobileNavigation';
import { BotView, MatchView, OnlineRoomView } from './p2';
import './styles.css';

const WEEKDAY_LABELS = ['일', '월', '화', '수', '목', '금', '토'];
const EXP_LABEL = {
  ALL_LEVELS: '경험 무관',
  BEGINNER_WELCOME: '초보 환영',
  EXPERIENCED_PREFERRED: '경험자 위주'
};
const TYPE_LABEL = { GAME_FOCUSED: '게임 중심', PERSON_FOCUSED: '사람 중심' };
const STATUS_LABEL = { RECRUITING: '모집 중', CLOSED: '모집 마감', CANCELED: '취소됨', FINISHED: '종료됨' };
const MAX_CAPACITY = 10;
// 하위 화면은 하단 탭바 없이 뒤로가기로만 돌아간다.
const SUB_ROUTES = ['game', 'game-rankings', 'session', 'create', 'edit', 'my', 'chat', 'chats', 'notifications', 'social-link', 'auth', 'signup', 'bot', 'match', 'online-room'];
const loadFirstNotificationPage = (signal) => api.getNotifications({ page: 0, size: 10 }, signal);
export const CHAT_SEND_REQUEST_DEADLINE_MS = 3_000;
export const WAITLIST_POLL_INTERVAL_MS = 10_000;
export const CHAT_SEND_RESULT_UNKNOWN_MESSAGE = '전송 여부를 확인하지 못했어요. 다시 시도해주세요.';
// 스플래시는 세션 확인이 끝나면 사라진다. 너무 빨리 사라져 깜빡이지 않도록 최소 표시 시간만 둔다.
const SPLASH_MIN_MS = 300;
// 회원가입 비밀번호 한도는 서버 검증 규칙과 같은 값을 쓴다. 한쪽만 바뀌면 안내와 결과가 어긋난다.
const PASSWORD_MIN_CODE_POINTS = 15;
const PASSWORD_MAX_CODE_POINTS = 64;
const PASSWORD_MAX_UTF8_BYTES = 72;
const SOCIAL_PROVIDER_LABEL = { GOOGLE: 'Google', NAVER: 'Naver', KAKAO: 'Kakao' };

function GoogleIcon() {
  return (
    <svg viewBox="0 0 20 20" width="24" height="24" aria-hidden="true">
      <path fill="#4285F4" d="M19.6 10.23c0-.68-.06-1.33-.17-1.96H10v3.71h5.4a4.62 4.62 0 0 1-2 3.03v2.52h3.24c1.9-1.75 2.96-4.33 2.96-7.3z" />
      <path fill="#34A853" d="M10 20c2.7 0 4.96-.89 6.62-2.42l-3.24-2.52c-.9.6-2.05.96-3.38.96-2.6 0-4.8-1.75-5.59-4.11H1.06v2.6A10 10 0 0 0 10 20z" />
      <path fill="#FBBC05" d="M4.41 11.9A5.99 5.99 0 0 1 4.09 10c0-.66.11-1.3.32-1.9V5.5H1.06A9.98 9.98 0 0 0 0 10c0 1.61.39 3.14 1.06 4.5l3.35-2.6z" />
      <path fill="#EA4335" d="M10 3.96c1.47 0 2.79.5 3.83 1.49l2.87-2.87C14.95.98 12.7 0 10 0 6.09 0 2.71 2.24 1.06 5.5l3.35 2.6C5.2 5.71 7.4 3.96 10 3.96z" />
    </svg>
  );
}
function NaverIcon() {
  return (
    <svg viewBox="0 0 20 20" width="22" height="22" aria-hidden="true">
      <path fill="#fff" d="M11.6 5v5.3L8.4 5H6v10h2.4v-5.3l3.2 5.3H14V5z" />
    </svg>
  );
}
function KakaoIcon() {
  return (
    <svg viewBox="0 0 20 20" width="26" height="26" aria-hidden="true">
      <path fill="#391B1B" d="M10 4.8c-3.31 0-6 2.13-6 4.76 0 1.7 1.14 3.2 2.85 4.05-.13.46-.46 1.63-.53 1.88-.08.31.11.31.24.22.1-.07 1.62-1.1 2.28-1.55.37.05.75.08 1.16.08 3.31 0 6-2.13 6-4.76s-2.69-4.68-6-4.68z" />
    </svg>
  );
}
const SOCIAL_PROVIDER_ICON = { GOOGLE: <GoogleIcon />, NAVER: <NaverIcon />, KAKAO: <KakaoIcon /> };

// 채팅 진입은 서버가 매 요청마다 다시 판정한다. 거절 사유는 코드로만 구분해 안내한다.
const CHAT_ACCESS_MESSAGE = {
  FORBIDDEN: '지금은 이 모임의 채팅에 들어갈 수 없어요. 참가 중인 모집 중·마감 모임에서만 채팅할 수 있어요.',
  ROOM_NOT_FOUND: '모임을 찾을 수 없어 채팅을 열 수 없어요.',
  VALIDATION_ERROR: '채팅 주소가 올바르지 않아요.'
};
const createClientMessageId = () => globalThis.crypto?.randomUUID?.() || 'chat-' + Date.now() + '-' + Math.random().toString(36).slice(2);
// callback이 돌려주는 고정 결과다. 여기 없는 값과 제공자 오류 설명은 해석하지도 보여주지도 않는다.
const SOCIAL_AUTH_RESULT = {
  'login-success': { message: '로그인했어요.', type: '' },
  'link-success': { message: '소셜 계정을 연결했어요.', type: '' },
  'link-required': { message: '같은 이메일을 쓰는 계정이 이미 있어요. 로그인한 뒤 내정보에서 연결해주세요.', type: 'err' },
  'link-conflict': { message: '이미 연결된 계정이 있어 연결하지 못했어요.', type: 'err' },
  canceled: { message: '동의를 취소해서 중단했어요.', type: '' },
  'invalid-state': { message: '요청이 만료됐어요. 처음부터 다시 시도해주세요.', type: 'err' },
  'provider-unavailable': { message: '지금은 사용할 수 없는 로그인 방법이에요.', type: 'err' },
  failed: { message: '요청을 끝내지 못했어요. 잠시 후 다시 시도해주세요.', type: 'err' }
};
const ROOM_TYPE_FILTERS = [
  { value: '', label: '전체' },
  { value: 'GAME_FOCUSED', label: '게임 중심' },
  { value: 'PERSON_FOCUSED', label: '사람 중심' }
];
const ROOM_STATUS_FILTER_LABEL = { RECRUITING: '모집 중', CLOSED: '모집 마감' };
// Asia/Seoul은 일광절약시간을 쓰지 않아 오프셋이 항상 같다.
const SEOUL_OFFSET = '+09:00';
// 라디오 그룹이 실제 조건을 그대로 나타내도록, 특정 날짜를 고른 상태도 선택지 하나로 둔다.
const DATE_EXACT = 'EXACT';
const DATE_PRESET_LABEL = {
  TODAY: '오늘',
  WEEKEND: '이번 주말',
  THIS_WEEK: '이번 주'
};
// 프리셋과 지정 날짜는 함께 쓰지 않는다. 한쪽을 고르면 다른 쪽은 비운다.
const EMPTY_ROOM_FILTERS = {
  status: '',
  datePreset: '',
  date: '',
  minRemainingSeats: '',
  experienceLevel: '',
  rulemasterOnly: false
};

function zeroPad(value) {
  return String(value).padStart(2, '0');
}

function dateParts(isoDate) {
  const matched = /^(\d{4})-(\d{2})-(\d{2})$/.exec(isoDate || '');
  if (!matched) return null;

  const year = Number(matched[1]);
  const monthIndex = Number(matched[2]) - 1;
  const day = Number(matched[3]);
  const date = new Date(Date.UTC(year, monthIndex, day));
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== monthIndex || date.getUTCDate() !== day) return null;
  return { year, monthIndex, day };
}

function isoDateFromParts(year, monthIndex, day) {
  return year + '-' + zeroPad(monthIndex + 1) + '-' + zeroPad(day);
}

function zonedDateParts(value) {
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  const values = {};
  new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23'
  }).formatToParts(date).forEach((part) => {
    values[part.type] = part.value;
  });
  return {
    year: Number(values.year),
    monthIndex: Number(values.month) - 1,
    day: Number(values.day),
    hour: values.hour,
    minute: values.minute
  };
}

function isoDateInSeoul(value) {
  const parts = zonedDateParts(value);
  return parts ? isoDateFromParts(parts.year, parts.monthIndex, parts.day) : '';
}

function timeInSeoul(value) {
  const parts = zonedDateParts(value);
  return parts ? parts.hour + ':' + parts.minute : '';
}

function todayInSeoul() {
  return isoDateInSeoul(new Date());
}

function addIsoDays(isoDate, days) {
  const parts = dateParts(isoDate);
  if (!parts) return '';
  const moved = new Date(Date.UTC(parts.year, parts.monthIndex, parts.day + days));
  return isoDateFromParts(moved.getUTCFullYear(), moved.getUTCMonth(), moved.getUTCDate());
}

function nextIsoDate(isoDate) {
  return addIsoDays(isoDate, 1);
}

// 0은 일요일, 6은 토요일이다.
function weekdayOf(isoDate) {
  const parts = dateParts(isoDate);
  return parts ? new Date(Date.UTC(parts.year, parts.monthIndex, parts.day)).getUTCDay() : null;
}

// 프리셋을 오늘 기준 반열린 구간 [시작 날짜, 끝 날짜)로 바꾼다. 끝 날짜는 결과에 포함하지 않는다.
function datePresetRange(preset, today) {
  const weekday = weekdayOf(today);
  if (weekday === null) return null;
  if (preset === 'TODAY') return { from: today, to: nextIsoDate(today) };
  // 주말은 다가오는 토요일과 일요일이다. 토·일 당일에는 남은 주말만 남긴다.
  if (preset === 'WEEKEND') {
    if (weekday === 0) return { from: today, to: nextIsoDate(today) };
    const saturday = addIsoDays(today, 6 - weekday);
    return { from: saturday, to: addIsoDays(saturday, 2) };
  }
  // 이번 주는 오늘부터 다가오는 일요일까지다. 일요일 당일에는 오늘 하루다.
  if (preset === 'THIS_WEEK') return { from: today, to: addIsoDays(today, (7 - weekday) % 7 + 1) };
  return null;
}

function defaultRoomDate(today = todayInSeoul()) {
  return nextIsoDate(today);
}

// 날짜 필터는 일 단위로 고르고, 요청에는 Asia/Seoul 기준 해당 날짜 00:00의 오프셋 있는 시각을 보낸다.
function seoulDayStart(isoDate) {
  return dateParts(isoDate) ? isoDate + 'T00:00:00' + SEOUL_OFFSET : undefined;
}

function millisecondsUntilNextSeoulMidnight() {
  const now = new Date();
  const parts = zonedDateParts(now);
  if (!parts) return 60 * 1000;
  const nextMidnight = Date.UTC(parts.year, parts.monthIndex, parts.day + 1) - 9 * 60 * 60 * 1000;
  return Math.max(1000, nextMidnight - now.getTime() + 100);
}

function useSeoulToday() {
  const [today, setToday] = useState(todayInSeoul);
  useEffect(() => {
    const timer = window.setTimeout(() => setToday(todayInSeoul()), millisecondsUntilNextSeoulMidnight());
    return () => window.clearTimeout(timer);
  }, [today]);
  return today;
}

function formatRoomDate(isoDate) {
  const parts = dateParts(isoDate);
  if (!parts) return '';
  const weekday = new Date(Date.UTC(parts.year, parts.monthIndex, parts.day)).getUTCDay();
  return (parts.monthIndex + 1) + '/' + parts.day + '(' + WEEKDAY_LABELS[weekday] + ')';
}

function formatStartsAt(startsAt) {
  const date = isoDateInSeoul(startsAt);
  const dateLabel = date === todayInSeoul() ? '오늘' : formatRoomDate(date);
  const time = timeInSeoul(startsAt);
  return [dateLabel, time].filter(Boolean).join(' ');
}

function timeParts(time) {
  const [hour24, minute] = time.split(':').map(Number);
  return { isAfternoon: hour24 >= 12, hour: hour24 % 12 === 0 ? 12 : hour24 % 12, minute };
}

function formatRoomTime(time) {
  const parts = timeParts(time);
  return (parts.isAfternoon ? '오후' : '오전') + ' ' + parts.hour + ':' + zeroPad(parts.minute);
}

export function readSocialAuthResult(search) {
  const value = new URLSearchParams(search).get('socialAuth');
  return value && Object.hasOwn(SOCIAL_AUTH_RESULT, value) ? SOCIAL_AUTH_RESULT[value] : null;
}

// 결과를 읽는 즉시 query를 지운다. 허용하지 않는 값이 섞여 와도 주소와 히스토리에 남기지 않는다.
export function consumeSocialAuthResult() {
  const result = readSocialAuthResult(window.location.search);
  if (window.location.search) {
    window.history.replaceState(window.history.state, '', window.location.pathname + window.location.hash);
  }
  return result;
}

function parseRoute() {
  const path = (window.location.hash || '#/home').slice(2);
  const parts = path.split('/');
  return { route: parts[0] || 'home', arg: parts[1] };
}

function useHashRoute() {
  const [location, setLocation] = useState(parseRoute);

  useEffect(() => {
    const updateLocation = () => setLocation(parseRoute());
    if (!window.location.hash) window.location.hash = '#/home';
    window.addEventListener('hashchange', updateLocation);
    return () => window.removeEventListener('hashchange', updateLocation);
  }, []);

  // 기본은 히스토리에 쌓는 이동이다. 이미 끝나 다시 돌아갈 이유가 없는 화면은 replace로 현재 항목을 대체해
  // 뒤로 가기가 방금 끝낸 화면으로 되돌아가지 않게 한다. replace도 hashchange를 발생시켜 구독은 그대로 동작한다.
  const navigate = (path, { replace = false } = {}) => {
    const hash = path.startsWith('#') ? path : '#' + path;
    if (replace) window.location.replace(hash);
    else window.location.hash = hash;
  };

  return [location, navigate];
}

function activeParticipantCount(room) {
  return Math.max(0, room.participantCount - 1);
}

function participantCount(room) {
  return room.participantCount;
}

// 자리 수는 주최자를 포함한다. 모집 정원은 주최자를 뺀 값이다.
function totalSeats(room) {
  return room.recruitmentCapacity + 1;
}

function isHost(room) {
  return room.myRole === 'HOST';
}

function isJoined(room) {
  return room.myRole === 'JOINED';
}

function hasStarted(room) {
  return Date.now() >= Date.parse(room.startsAt);
}

function sessionStatus(room) {
  return room.status;
}

function roomStatusLabel(room) {
  if (sessionStatus(room) === 'RECRUITING') return room.remainingRecruitmentSeats <= 0 ? '자리 마감' : '';
  return STATUS_LABEL[sessionStatus(room)] || '';
}

function canEdit(room) {
  return isHost(room)
    && sessionStatus(room) === 'RECRUITING'
    && !hasStarted(room)
    && activeParticipantCount(room) === 0;
}

function startsAtFromDateAndTime(date, time) {
  return dateParts(date) && /^\d{2}:\d{2}$/.test(time) ? date + 'T' + time + ':00+09:00' : null;
}

function roomFormFromRoom(room, initialGame = null) {
  const game = room?.game || initialGame;
  return {
    gameId: game?.id || '',
    selectedGame: game || null,
    title: room?.title || '',
    description: room?.description || '',
    date: room ? isoDateInSeoul(room.startsAt) : defaultRoomDate(),
    time: room ? timeInSeoul(room.startsAt) : '19:00',
    place: room?.place || '',
    recruitmentCapacity: room?.recruitmentCapacity || 3,
    experienceLevel: room?.experienceLevel || 'BEGINNER_WELCOME',
    isRulemasterLed: room?.isRulemasterLed || false
  };
}

function validateRoomForm(form, roomType) {
  const startsAt = startsAtFromDateAndTime(form.date, form.time);
  const room = {
    title: form.title.trim(),
    description: form.description.trim(),
    gameId: form.gameId ? Number(form.gameId) : null,
    experienceLevel: form.experienceLevel,
    isRulemasterLed: Boolean(form.isRulemasterLed),
    startsAt,
    place: form.place.trim(),
    recruitmentCapacity: Number(form.recruitmentCapacity)
  };

  if (roomType === 'GAME_FOCUSED' && !room.gameId) return { error: '게임 중심 모임은 게임을 꼭 선택해야 해요.' };
  if (!room.title) return { error: '모임 제목을 입력해주세요.' };
  if (room.title.length > 100) return { error: '모임 제목은 100자 이내로 입력해주세요.' };
  if (room.description.length > 255) return { error: '설명은 255자 이내로 입력해주세요.' };
  if (!room.place) return { error: '장소를 입력해주세요.' };
  if (room.place.length > 100) return { error: '장소는 100자 이내로 입력해주세요.' };
  if (!room.startsAt || Date.parse(room.startsAt) <= Date.now()) return { error: '시작 시간은 현재 시각 이후여야 해요.' };
  if (!Number.isInteger(room.recruitmentCapacity) || room.recruitmentCapacity < 1 || room.recruitmentCapacity > MAX_CAPACITY) return { error: '모집 정원은 본인 제외 1~10명이어야 해요.' };
  return { room };
}

/** 홈·게임 찾기 타이틀 줄 오른쪽에 붙는 알림·채팅 진입. */
function HeaderActions({ unreadCount, chatUnreadCount, onOpenNotifications }) {
  return (
    <span className="appbar-actions">
      <button
        type="button"
        className="icon-btn"
        aria-label={unreadCount > 0 ? '알림함, 읽지 않은 알림 ' + unreadCount + '개' : '알림함'}
        onClick={onOpenNotifications}
      >
        <BellIcon />
        {unreadCount > 0 && <span className="unread-dot" aria-hidden="true" />}
      </button>
      <a
        className="icon-btn"
        href="#/chats"
        aria-label={chatUnreadCount > 0 ? '전체 채팅, 읽지 않은 채팅방 ' + chatUnreadCount + '개' : '전체 채팅'}
      >
        <ChatIcon />
        {chatUnreadCount > 0 && <span className="unread-dot" aria-hidden="true" />}
      </a>
    </span>
  );
}

function LoginRequiredView({ message = '이 기능은 로그인 후 이용할 수 있어요.', onBack }) {
  return (
    <div className="screen sub">
      <TopBar onBack={onBack} />
      <div className="screen-body pad-bottom">
        <StateBlock title="로그인이 필요해요" description={message}>
          <a className="btn" href="#/auth">로그인 또는 회원가입</a>
        </StateBlock>
      </div>
    </div>
  );
}

/** 모임 목록 한 줄. 표지·시각·배지·제목·게임과 장소·자리 미플을 한 규격으로 둔다. */
function RoomListItem({ room, showHostBadge = false, actions }) {
  const statusLabel = roomStatusLabel(room);
  return (
    <div>
      <a className="roomrow" href={'#/session/' + room.id}>
        <Cover src={room.game?.imageUrl} />
        <span className="roomrow-body">
          <span className="roomrow-top">
            <span className="roomrow-when">{formatStartsAt(room.startsAt)}</span>
            {showHostBadge && isHost(room) && <span className="badge ink">내가 개설</span>}
            {room.isRulemasterLed && <span className="badge">룰마스터</span>}
            {statusLabel && <span className="badge muted">{statusLabel}</span>}
          </span>
          <span className="roomrow-title">{room.title}</span>
          <span className="roomrow-meta">{(room.game?.title || TYPE_LABEL[room.roomType]) + ' · ' + (room.place || room.region || '장소 미정')}</span>
          <span className="roomrow-seats">
            <Meeples filled={participantCount(room)} total={totalSeats(room)} />
            <SeatCount filled={participantCount(room)} total={totalSeats(room)} />
          </span>
        </span>
      </a>
      {actions && <div className="roomrow-actions">{actions}</div>}
    </div>
  );
}

function HomeView({ me, unreadCount, chatUnreadCount, onOpenNotifications, dataVersion }) {
  return (
    <div className="screen">
      <div className="appbar">
        <span className="appbar-brand"><BrandMark /><span>알밤메이트</span></span>
        <HeaderActions unreadCount={unreadCount} chatUnreadCount={chatUnreadCount} onOpenNotifications={onOpenNotifications} />
      </div>
      <div className="screen-body pad-bottom">
        <MobileHomePanel me={me} dataVersion={dataVersion} />
      </div>
    </div>
  );
}

function roomFilterParameters(filters, today) {
  const range = datePresetRange(filters.datePreset, today) || { from: filters.date, to: nextIsoDate(filters.date) };
  return {
    status: filters.status,
    startsAtFrom: seoulDayStart(range.from),
    startsAtTo: seoulDayStart(range.to),
    minRemainingSeats: filters.minRemainingSeats,
    // 파라미터 이름은 계약대로 복수형이며 화면에서는 한 값만 고른다.
    experienceLevels: filters.experienceLevel,
    rulemasterOnly: filters.rulemasterOnly
  };
}

// 요청에 실제로 담기는 값만 비교해 조회 의존성을 판정한다.
function roomFilterKey(filters, today) {
  return JSON.stringify(roomFilterParameters(filters, today));
}

function roomFilterChips(filters, roomType) {
  const chips = [];
  if (roomType) chips.push('type');
  if (filters.status) chips.push('status');
  if (filters.datePreset) chips.push('datePreset');
  if (filters.date) chips.push('date');
  if (filters.minRemainingSeats) chips.push('seats');
  if (filters.experienceLevel) chips.push('experience');
  if (filters.rulemasterOnly) chips.push('rulemaster');
  return chips;
}

function RoomFilters({ filters, onChange, today, roomType, onRoomTypeChange, counts, resultCount }) {
  const update = (patch) => onChange({ ...filters, ...patch });
  const withCount = (filter) => filter.label + (counts ? ' (' + counts[filter.value] + ')' : '');
  const datePresetChips = (
    <>
      {[{ value: '', label: '전체' }, ...Object.entries(DATE_PRESET_LABEL).map(([code, label]) => ({ value: code, label }))].map((option) => (
        <button
          type="button"
          key={option.value || 'all'}
          className={'chip' + (!filters.date && filters.datePreset === option.value ? ' on' : '')}
          aria-pressed={!filters.date && filters.datePreset === option.value}
          onClick={() => update({ datePreset: option.value, date: '' })}
        >
          {option.label}
        </button>
      ))}
    </>
  );
  return (
    <FilterPanel
      title="필터"
      chips={roomFilterChips(filters, roomType)}
      quickSlot={datePresetChips}
      onReset={() => { onRoomTypeChange(''); onChange(EMPTY_ROOM_FILTERS); }}
      ctaLabel={Number.isFinite(resultCount) ? resultCount + '개 모임 보기' : '모임 보기'}
    >
      <FilterRadioGroup name="room-filter-type" label="모임 성격" value={roomType} onChange={onRoomTypeChange}
        options={ROOM_TYPE_FILTERS.map((filter) => ({ value: filter.value, label: withCount(filter) }))} />
      <FilterRadioGroup name="room-filter-status" label="모집 상태" value={filters.status} onChange={(status) => update({ status })}
        options={[{ value: '', label: '전체' }, ...Object.entries(ROOM_STATUS_FILTER_LABEL).map(([code, label]) => ({ value: code, label }))]} />
      <FilterRadioGroup name="room-filter-date" label="날짜" value={filters.date ? DATE_EXACT : filters.datePreset}
        onChange={(value) => update(value === DATE_EXACT ? { datePreset: '', date: defaultRoomDate(today) } : { datePreset: value, date: '' })}
        options={[
          { value: '', label: '전체' },
          ...Object.entries(DATE_PRESET_LABEL).map(([code, label]) => ({ value: code, label })),
          { value: DATE_EXACT, label: '날짜 지정' }
        ]}>
        {!!filters.date && (
          <div style={{ marginTop: 11 }}>
            <label className="sr-only" htmlFor="room-filter-date-exact">날짜 지정</label>
            <input id="room-filter-date-exact" className="field-input" type="date" min={today} value={filters.date} onChange={(event) => update({ date: event.target.value, datePreset: '' })} />
          </div>
        )}
      </FilterRadioGroup>
      <FilterRadioGroup name="room-filter-seats" label="남은 자리" value={filters.minRemainingSeats} onChange={(minRemainingSeats) => update({ minRemainingSeats })}
        options={[{ value: '', label: '전체' }, ...Array.from({ length: MAX_CAPACITY }, (_, index) => ({ value: String(index + 1), label: index + 1 + '자리 이상' }))]} />
      <FilterRadioGroup name="room-filter-experience" label="경험 수준" value={filters.experienceLevel} onChange={(experienceLevel) => update({ experienceLevel })}
        options={[{ value: '', label: '전체' }, ...Object.entries(EXP_LABEL).map(([code, label]) => ({ value: code, label }))]} />
      <FilterCheckGroup label="진행" checked={filters.rulemasterOnly} onChange={(rulemasterOnly) => update({ rulemasterOnly })} text="룰마스터 있는 모임만" />
    </FilterPanel>
  );
}

// 유형 필터에 붙일 개수. 방 유형은 두 가지뿐이라 전체는 둘의 합으로 구한다.
function useRoomTypeCounts(keyword, filters, today, dataVersion) {
  const [counts, setCounts] = useState(null);
  const filterKey = roomFilterKey(filters, today);
  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    const parameters = roomFilterParameters(filters, today);
    Promise.all([
      api.getRooms({ type: 'GAME_FOCUSED', keyword, ...parameters, page: 0, size: 1 }, controller.signal),
      api.getRooms({ type: 'PERSON_FOCUSED', keyword, ...parameters, page: 0, size: 1 }, controller.signal)
    ])
      .then(([game, person]) => {
        if (!active) return;
        const gameCount = game?.totalElements ?? 0;
        const personCount = person?.totalElements ?? 0;
        setCounts({ '': gameCount + personCount, GAME_FOCUSED: gameCount, PERSON_FOCUSED: personCount });
      })
      .catch(() => { if (active) setCounts(null); });
    return () => { active = false; controller.abort(); };
  }, [keyword, filterKey, dataVersion]);
  return counts;
}

export function FindRoomsView({ roomType, onRoomTypeChange, roomQuery, onRoomQueryChange, roomFilters, onRoomFiltersChange, dataVersion }) {
  const [input, setInput] = useState(roomQuery);
  const keyword = roomQuery.trim();
  const today = useSeoulToday();
  const filterKey = roomFilterKey(roomFilters, today);
  const counts = useRoomTypeCounts(keyword, roomFilters, today, dataVersion);
  const { items, total, hasNext, loading, error, loadMore, retry } = useCumulativeRequest(
    // 유형을 비우면 두 유형의 공개 방을 함께 받는다.
    (page, signal) => api.getRooms({ type: roomType, keyword, ...roomFilterParameters(roomFilters, today), page, size: ROOM_LIST_PAGE_SIZE }, signal),
    [roomType, keyword, filterKey, dataVersion]
  );
  // status는 서버가 페이지네이션 전에 거른다(roomFilterParameters). 기본값(전체)에서는 프런트가 상태로 다시 거르지 않아
  // 모집 마감이라도 대기 신청이 가능한 방의 진입점이 유지된다.
  const rooms = items.map(normalizeRoom);
  const resetFilters = () => {
    onRoomTypeChange('');
    onRoomQueryChange('');
    onRoomFiltersChange(EMPTY_ROOM_FILTERS);
  };
  useEffect(() => setInput(roomQuery), [roomQuery]);
  return (
    <div className="screen">
      <div className="screen-body pad-top pad-bottom">
        <ScreenTitle actions={(
          <span className="appbar-actions">
            <a className="icon-btn" href="#/create" aria-label="모임 만들기">
              <span className="round-plus"><PlusIcon /></span>
            </a>
          </span>
        )}
        >
          모임 찾기
        </ScreenTitle>
        <form
          className="searchbox"
          style={{ marginTop: 16 }}
          onSubmit={(event) => { event.preventDefault(); onRoomQueryChange(input.trim()); }}
        >
          <SearchIcon />
          <label className="sr-only" htmlFor="room-q">모임 제목 검색</label>
          <input id="room-q" value={input} onChange={(event) => setInput(event.target.value)} placeholder="게임, 모임 제목, 지역" />
        </form>
        <RoomFilters filters={roomFilters} onChange={onRoomFiltersChange} today={today} roomType={roomType} onRoomTypeChange={onRoomTypeChange} counts={counts} resultCount={total} />
        {!error && <p className="section-label" style={{ marginTop: 18 }}>{loading && !rooms.length ? '불러오는 중' : '모임 ' + total + '개'}</p>}

        <div style={{ marginTop: 18 }}>
          {error && <ErrorBox title="모임을 불러오지 못했어요" message={error} onRetry={retry} />}
          {!error && loading && !rooms.length && <RoomSkeletons label="모임 목록을 불러오는 중" />}
          {!error && !!rooms.length && (
            <div className="roomlist">
              {rooms.map((room) => <RoomListItem key={room.id} room={room} />)}
            </div>
          )}
          {!error && !loading && !rooms.length && (
            <StateBlock title={<>{'조건에 맞는 '}<br />{'모임이 없어요'}</>} description="필터를 하나만 풀어도 더 많이 나와요. 직접 모임을 열어 사람을 모아도 좋고요.">
              <div className="btn-row">
                <button className="btn fill" type="button" onClick={resetFilters}>필터 초기화</button>
                <a className="btn" href="#/create">모임 만들기</a>
              </div>
            </StateBlock>
          )}
          {!error && hasNext && (
            <button type="button" className="more-btn" style={{ marginTop: 22 }} disabled={loading} onClick={loadMore}>
              {loading ? '불러오는 중…' : (total - rooms.length) + '개 더 보기'}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

/** 대기열 카드와 sticky CTA. 상태 판정은 서버 응답만 따른다. */
// 안내 카드는 본문과 함께 스크롤되고 하단 바만 고정된다. 두 자리가 상태를 나눠 쓰므로 훅으로 한 번만 계산한다.
function useSessionActions({ room, roomRefreshing, me, onApply, onCancelApply, onHostCancel, onFinish, onJoinWaitlist, onCancelWaitlist, onWaitlistSettled }) {
  const [pending, setPending] = useState(false);
  const [waitlistVersion, setWaitlistVersion] = useState(0);
  const status = sessionStatus(room);
  const eligibleForWaitlist = Boolean(me) && !isHost(room) && !isJoined(room);
  const { data: waitlist, loading: waitlistLoading, error: waitlistError } = useRequest(
    async (signal) => {
      if (!eligibleForWaitlist) return null;
      try {
        return await api.getMyWaitlist(room.id, signal);
      } catch (error) {
        if (error instanceof ApiError && error.status === 404 && error.code === 'WAITLIST_ENTRY_NOT_FOUND') return null;
        throw error;
      }
    },
    [room.id, eligibleForWaitlist, waitlistVersion]
  );
  const waitlistStatus = waitlist?.waitlistStatus ?? null;
  const waiting = waitlistStatus === 'WAITING';
  const promoted = waitlistStatus === 'PROMOTED';
  // EXPIRED·ROOM_CANCELED는 재활성화하지 않는 종료 상태라 재신청 대상이 아니다(PART-04).
  const waitlistEnded = waitlistStatus === 'EXPIRED' || waitlistStatus === 'ROOM_CANCELED';
  // 승격·만료·방 취소는 이 화면 밖에서 일어나므로 WAITING 동안 대기 상태를 다시 읽는다.
  useEffect(() => {
    if (!waiting) return undefined;
    const timer = window.setInterval(() => setWaitlistVersion((version) => version + 1), WAITLIST_POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [waiting]);
  // WAITING을 벗어나면 상세의 역할·행동 가능 여부도 함께 바뀌므로 상세를 한 번 다시 읽는다.
  useEffect(() => {
    if (waitlistStatus && !waiting) onWaitlistSettled?.();
  }, [waitlistStatus]);
  const run = (action) => async () => {
    setPending(true);
    try {
      await action(room.id);
    } finally {
      setPending(false);
    }
  };
  const runWaitlist = (action) => async () => {
    setPending(true);
    try {
      await action(room.id);
    } finally {
      // 실패도 경합으로 대기 상태가 이미 바뀐 결과일 수 있어 성공·실패 모두 다시 읽는다.
      setWaitlistVersion((version) => version + 1);
      setPending(false);
    }
  };
  const canEnterChat = Boolean(room.myRole && (status === 'RECRUITING' || status === 'CLOSED'));
  const chatButton = canEnterChat
    ? <a className="btn-square fill" href={'#/chat/' + room.id} aria-label="모임 채팅"><ChatIcon size={20} /></a>
    : null;
  // card는 본문 안, bar는 본문 밖 고정 자리에 그려진다.
  const bar = (cta, card = null) => ({ card, bar: <div className="stickybar">{chatButton}{cta}</div> });

  if (!me) {
    return bar(<a className="btn cta" href="#/auth">로그인하고 참가하기</a>);
  }

  if (isHost(room)) {
    if (status === 'RECRUITING' || status === 'CLOSED') {
      const finishable = status === 'CLOSED' && hasStarted(room);
      return bar(
        finishable
          ? <button className="btn cta" type="button" disabled={pending} onClick={run(onFinish)}>{pending ? '처리 중…' : '모임 종료하기'}</button>
          : <button className="btn cta off" type="button" disabled>{status === 'CLOSED' ? '모집이 마감됐어요' : '참가자를 기다리는 중'}</button>,
        <div className="notecard">
          <strong>내가 연 모임이에요</strong>
          <p>{finishable ? '모임이 끝났다면 종료해주세요. 취소는 참가자에게 알림이 갑니다.' : '취소하면 참가자에게 알림이 가고 되돌릴 수 없어요.'}</p>
          <button className="btn fill" type="button" disabled={pending} onClick={run(onHostCancel)}>{pending ? '처리 중…' : '모임 취소'}</button>
        </div>
      );
    }
    return bar(<button className="btn cta off" type="button" disabled>{status === 'FINISHED' ? '종료된 모임이에요' : '취소된 모임이에요'}</button>);
  }

  if (status === 'CANCELED' || status === 'FINISHED') {
    return bar(<button className="btn cta off" type="button" disabled>{status === 'CANCELED' ? '취소된 모임이에요' : '종료된 모임이에요'}</button>);
  }

  if (isJoined(room)) {
    const cancelable = (status === 'RECRUITING' || status === 'CLOSED') && !hasStarted(room);
    return bar(cancelable
      ? <button className="btn cta fill" type="button" disabled={pending} onClick={run(onCancelApply)}>{pending ? '처리 중…' : '참가 취소'}</button>
      : <button className="btn cta off" type="button" disabled>참가 중이에요</button>);
  }

  if (room.joinable) {
    return bar(<button className="btn cta" type="button" disabled={pending} onClick={run(onApply)}>{pending ? '처리 중…' : '참가하기'}</button>);
  }

  const waitlistCard = () => {
    if (promoted) {
      return (
        <div className="notecard green">
          <strong>대기가 자리로 승격되어 참가가 확정됐어요</strong>
          <p>채팅이 열렸어요. 시간과 장소를 확인해주세요.</p>
        </div>
      );
    }
    if (waitlistError) {
      return (
        <div className="notecard red">
          <strong>대기 상태를 확인하지 못했어요</strong>
          <p>{waitlistError}</p>
          <button className="btn fill" type="button" onClick={() => setWaitlistVersion((version) => version + 1)}>다시 시도</button>
        </div>
      );
    }
    if (waiting) {
      return (
        <div className="notecard">
          <strong className="lg">대기 {waitlist.position}번째입니다.</strong>
          <p>자리가 나면 자동으로 참가돼요. 취소하면 대기 순번이 사라져요.</p>
          <button className="btn white" type="button" disabled={pending} onClick={runWaitlist(onCancelWaitlist)}>{pending ? '처리 중…' : '대기 취소'}</button>
        </div>
      );
    }
    if (waitlistEnded) {
      return <div className="notecard gray">{waitlistStatus === 'ROOM_CANCELED' ? '모임이 취소되어 대기가 종료됐어요.' : '모임이 시작되어 대기가 종료됐어요.'}</div>;
    }
    // 상세를 다시 읽는 동안에는 이전 응답의 waitlistable로 대기 신청을 안내하지 않는다.
    if (room.waitlistable && !waitlistLoading && !roomRefreshing) {
      return (
        <div className="notecard">
          <strong>지금은 정원이 가득 찼어요</strong>
          <p>대기 신청하면 자리가 났을 때 순서대로 자동 참가돼요.</p>
          <button className="btn" type="button" disabled={pending} onClick={runWaitlist(onJoinWaitlist)}>{pending ? '처리 중…' : '대기 신청하기'}</button>
        </div>
      );
    }
    if (eligibleForWaitlist && (waitlistLoading || roomRefreshing)) {
      return <div className="notecard gray">참가 가능 여부를 확인하는 중…</div>;
    }
    return null;
  };

  return bar(
    <button className="btn cta off" type="button" disabled>{room.remainingRecruitmentSeats <= 0 ? '자리가 다 찼어요' : '지금은 참가할 수 없어요'}</button>,
    waitlistCard()
  );
}

export function SessionDetailView({ sessionId, me, onBack, onApply, onCancelApply, onHostCancel, onFinish, onJoinWaitlist, onCancelWaitlist, onWaitlistSettled, dataVersion }) {
  const { data, loading, error, retry } = useRequest(
    async (signal) => normalizeRoom(await api.getRoom(sessionId, signal)),
    [sessionId, dataVersion]
  );
  if (error) {
    return (
      <div className="screen sub">
        <TopBar onBack={onBack} />
        <div className="screen-body pad-bottom"><ErrorBox title="모임을 불러오지 못했어요" message={error} onRetry={retry} /></div>
      </div>
    );
  }
  if (loading && !data) {
    return (
      <div className="screen sub">
        <TopBar onBack={onBack} />
        <div className="screen-body pad-bottom"><RoomSkeletons count={2} /></div>
      </div>
    );
  }
  const room = data;
  if (!room) {
    return (
      <div className="screen sub">
        <TopBar onBack={onBack} />
        <div className="screen-body pad-bottom"><StateBlock title="모임을 찾을 수 없어요" description="주소를 다시 확인해주세요." /></div>
      </div>
    );
  }
  return (
    <SessionDetailContent
      room={room}
      roomRefreshing={loading}
      me={me}
      onBack={onBack}
      onApply={onApply}
      onCancelApply={onCancelApply}
      onHostCancel={onHostCancel}
      onFinish={onFinish}
      onJoinWaitlist={onJoinWaitlist}
      onCancelWaitlist={onCancelWaitlist}
      onWaitlistSettled={onWaitlistSettled}
    />
  );
}

// 조회 상태 분기를 위에서 끝내고, 방이 확정된 뒤에만 조작 훅을 부른다.
function SessionDetailContent({ room, roomRefreshing, me, onBack, onApply, onCancelApply, onHostCancel, onFinish, onJoinWaitlist, onCancelWaitlist, onWaitlistSettled }) {
  const actions = useSessionActions({
    room,
    roomRefreshing,
    me,
    onApply,
    onCancelApply,
    onHostCancel,
    onFinish,
    onJoinWaitlist,
    onCancelWaitlist,
    onWaitlistSettled
  });
  const privateView = Boolean(room.myRole);
  const game = room.game;
  const metaLine = [STATUS_LABEL[sessionStatus(room)], TYPE_LABEL[room.roomType], EXP_LABEL[room.experienceLevel]].filter(Boolean).join(' · ');
  // 호스트 닉네임은 참가자에게만 내려온다. 값이 없는 줄을 자리만 차지하게 두지 않는다.
  const facts = [
    { key: '일시', value: formatStartsAt(room.startsAt) },
    { key: '장소', value: privateView ? room.place : '참가 확정 후 확인할 수 있어요' },
    { key: '지역', value: room.region || '홍대' },
    room.host?.nickname && { key: '호스트', value: room.host.nickname + (isHost(room) ? ' (나)' : '') },
    { key: '진행', value: room.isRulemasterLed ? '룰마스터 진행' : '참가자끼리 진행' }
  ].filter(Boolean);

  return (
    <div className="screen sub">
      <TopBar
        onBack={onBack}
        action={canEdit(room) ? <a className="icon-btn" aria-label="모임 수정" href={'#/edit/' + room.id}><EditIcon /></a> : null}
      />
      <div className="screen-body" style={{ paddingBottom: 28 }}>
        <p className="room-meta">{metaLine}</p>
        <h1 className="room-title">{room.title}</h1>

        {game && (() => {
          // 모임 상세의 game은 요약이라 인원·시간이 없을 수 있다. 없으면 부제 줄을 만들지 않는다.
          const gameMetaLine = [game.players, game.time, game.complexity && '난이도 ' + game.complexity].filter(Boolean).join(' · ');
          return (
            <a className="room-game" href={'#/game/' + game.id}>
              <Cover src={game.imageUrl} />
              <span className="room-game-copy">
                <strong>{game.title}</strong>
                {gameMetaLine && <span>{gameMetaLine}</span>}
              </span>
              <span className="rowarrow"><ArrowIcon /></span>
            </a>
          );
        })()}

        <dl className="room-facts">
          {facts.map((fact) => <div key={fact.key}><dt>{fact.key}</dt><dd>{fact.value}</dd></div>)}
        </dl>

        {room.description && <p className="longtext room-description">{room.description}</p>}

        <h2 className="section-title" style={{ marginTop: 30 }}>
          참가자 {participantCount(room)} / {totalSeats(room)}
        </h2>
        <div style={{ marginTop: 15 }}>
          <Meeples filled={participantCount(room)} total={totalSeats(room)} size="lg" animate />
        </div>
        {privateView
          ? (
            <div className="room-members">
              {room.participants.map((participant, index) => (
                <div className="room-member" key={participant.nickname + '-' + index}>
                  <Avatar name={participant.nickname} index={index} imageUrl={participant.profileImageUrl} />
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div className="room-member-name" style={{ color: playerColor(index) }}>{participant.nickname}</div>
                    <div className="room-member-role">{participant.nickname === room.host?.nickname ? '호스트' : '참가자'}</div>
                  </div>
                  {participant.isMe && <span className="room-member-me">나</span>}
                </div>
              ))}
              {Array.from({ length: Math.max(0, totalSeats(room) - participantCount(room)) }, (_, index) => (
                <div className="room-member" key={'empty-' + index}>
                  <span className="avatar empty" aria-hidden="true">빈</span>
                  <div className="room-member-name" style={{ color: 'var(--muted)' }}>빈 자리</div>
                </div>
              ))}
            </div>
          )
          : <p className="screen-lead" style={{ marginTop: 18 }}>정확한 장소와 참가자 목록은 주최자 또는 현재 참가자만 확인할 수 있어요.</p>}
        {actions.card && <div className="session-notice">{actions.card}</div>}
      </div>
      {actions.bar}
    </div>
  );
}

function RoomFormFields({ form, onChange, roomType, onOpenGamePicker, today }) {
  const gameFocused = roomType === 'GAME_FOCUSED';
  const update = (field, value) => onChange({ ...form, [field]: value });
  const selectedGame = form.selectedGame;
  const capacity = Number(form.recruitmentCapacity);
  return (
    <>
      <div className="field">
        <span className="field-label">게임 {gameFocused ? '(필수)' : '(선택)'}</span>
        <button type="button" className="pickrow" onClick={onOpenGamePicker}>
          <Cover src={selectedGame?.imageUrl} />
          <span className="pickrow-copy">
            <strong className={selectedGame ? '' : 'empty'}>{selectedGame ? selectedGame.title : '게임 선택하기'}</strong>
            <span>{gameFocused ? '게임 중심 모임은 게임을 골라야 해요' : '게임 없이 모임을 열 수도 있어요'}</span>
          </span>
          <span className="rowarrow"><ArrowIcon /></span>
        </button>
      </div>

      <div className="field">
        <label className="field-label" htmlFor="room-title">제목</label>
        <input id="room-title" className="field-input" maxLength="100" value={form.title} onChange={(event) => update('title', event.target.value)} placeholder="예) 윙스팬 같이 하실 분" />
      </div>

      <div className="field field-row">
        <div>
          <label className="field-label" htmlFor="room-date">날짜</label>
          <input id="room-date" className="field-input" type="date" min={today} value={form.date} onChange={(event) => update('date', event.target.value)} />
        </div>
        <div>
          <label className="field-label" htmlFor="room-time">시작 시간</label>
          <input id="room-time" className="field-input" type="time" step="600" value={form.time} onChange={(event) => update('time', event.target.value)} />
        </div>
      </div>

      <div className="field">
        <label className="field-label" htmlFor="room-place">장소</label>
        <input id="room-place" className="field-input" maxLength="100" value={form.place} onChange={(event) => update('place', event.target.value)} placeholder="예) 주사위섬 합정점" />
        <p className="field-hint">지역은 홍대로 고정돼요.</p>
      </div>

      <div className="field">
        <span className="field-label" id="room-capacity-label">모집 인원 (본인 제외)</span>
        <div className="stepper" role="group" aria-labelledby="room-capacity-label">
          <button type="button" className="stepper-btn" aria-label="모집 인원 줄이기" disabled={capacity <= 1} onClick={() => update('recruitmentCapacity', Math.max(1, capacity - 1))}>−</button>
          <span className="stepper-value">
            <Meeples filled={capacity + 1} total={capacity + 1} size="md" />
          </span>
          <button type="button" className="stepper-btn" aria-label="모집 인원 늘리기" disabled={capacity >= MAX_CAPACITY} onClick={() => update('recruitmentCapacity', Math.min(MAX_CAPACITY, capacity + 1))}>+</button>
        </div>
        <p className="stepper-caption">나 포함 {capacity + 1}명 · {capacity}명 모집</p>
      </div>

      <div className="field">
        <span className="field-label">경험 수준</span>
        <div className="optionlist">
          {Object.entries(EXP_LABEL).map(([code, label]) => (
            <button
              type="button"
              key={code}
              className={'optionrow' + (form.experienceLevel === code ? ' on' : '')}
              aria-pressed={form.experienceLevel === code}
              onClick={() => update('experienceLevel', code)}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      <button type="button" className="switch-row" aria-pressed={form.isRulemasterLed} onClick={() => update('isRulemasterLed', !form.isRulemasterLed)}>
        <span className="switch-copy">
          <strong>룰 설명을 해줄 수 있어요</strong>
          <span>초보자에게 룰마스터로 표시됩니다</span>
        </span>
        <span className={'switch' + (form.isRulemasterLed ? ' on' : '')} aria-hidden="true"><span /></span>
      </button>

      <div className="field">
        <label className="field-label" htmlFor="room-description">소개</label>
        <textarea id="room-description" className="field-input" maxLength="255" value={form.description} onChange={(event) => update('description', event.target.value)} placeholder="어떤 분위기인지, 룰 설명이 필요한지 적어주세요." />
      </div>
    </>
  );
}

function CreateView({ createMode, onCreateModeChange, initialGame, onCreate, onBack, today }) {
  const [form, setForm] = useState(() => roomFormFromRoom(null, initialGame));
  const defaultDateRef = useRef(form.date);
  const [gamePickerOpen, setGamePickerOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const gameFocused = createMode === 'GAME_FOCUSED';
  useEffect(() => {
    if (!initialGame) return;
    setForm((current) => ({ ...current, gameId: initialGame.id, selectedGame: initialGame }));
  }, [initialGame]);
  useEffect(() => {
    const previousDefaultDate = defaultDateRef.current;
    const nextDefaultDate = defaultRoomDate(today);
    if (nextDefaultDate === previousDefaultDate) return;
    setForm((current) => current.date === previousDefaultDate ? { ...current, date: nextDefaultDate } : current);
    defaultDateRef.current = nextDefaultDate;
  }, [today]);
  const ready = Boolean(form.title.trim() && form.place.trim());
  const submit = async (event) => {
    event.preventDefault();
    setSubmitting(true);
    try {
      await onCreate(form);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form className="screen sub" onSubmit={submit}>
      <div className="topbar">
        <button type="button" className="icon-btn" aria-label="닫기" onClick={onBack}>
          <CloseIcon />
        </button>
      </div>
      <div className="screen-body" style={{ paddingBottom: 28 }}>
        <ScreenTitle>모임 만들기</ScreenTitle>
        <div className="field" style={{ marginTop: 28 }}>
          <span className="field-label">모임 성격</span>
          <div className="modecards">
            <button type="button" className={'modecard' + (gameFocused ? ' on' : '')} aria-pressed={gameFocused} onClick={() => onCreateModeChange('GAME_FOCUSED')}>
              <strong>게임 중심</strong>
              <span>게임을 먼저 정하고 사람을 모아요</span>
            </button>
            <button type="button" className={'modecard' + (!gameFocused ? ' on' : '')} aria-pressed={!gameFocused} onClick={() => onCreateModeChange('PERSON_FOCUSED')}>
              <strong>사람 중심</strong>
              <span>함께할 사람부터 모아요</span>
            </button>
          </div>
        </div>
        <RoomFormFields form={form} onChange={setForm} roomType={createMode} onOpenGamePicker={() => setGamePickerOpen(true)} today={today} />
      </div>
      <div className="stickybar">
        <button className={'btn cta' + (ready ? '' : ' off')} disabled={submitting} type="submit">
          {submitting ? '모임을 여는 중…' : ready ? '모임 열기' : '제목과 장소를 채워주세요'}
        </button>
      </div>
      <GamePickerDialog isOpen={gamePickerOpen} selectedGameId={form.gameId} allowClear={!gameFocused} onSelect={(game) => setForm((current) => ({ ...current, gameId: game.id, selectedGame: game }))} onClear={() => setForm((current) => ({ ...current, gameId: '', selectedGame: null }))} onClose={() => setGamePickerOpen(false)} />
    </form>
  );
}

function EditSessionForm({ room, onSave, onBack, today }) {
  const [form, setForm] = useState(() => roomFormFromRoom(room));
  const [gamePickerOpen, setGamePickerOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  if (!canEdit(room)) {
    return (
      <div className="screen sub">
        <TopBar onBack={onBack} />
        <div className="screen-body pad-bottom"><StateBlock title="지금은 수정할 수 없어요" description="시작 전이며 다른 활성 참가자가 없을 때만 수정할 수 있어요." /></div>
      </div>
    );
  }
  const submit = async (event) => {
    event.preventDefault();
    setSubmitting(true);
    try {
      await onSave(room.id, form, room.roomType);
    } finally {
      setSubmitting(false);
    }
  };
  return (
    <form className="screen sub" onSubmit={submit}>
      <TopBar onBack={onBack} title="모임 수정" />
      <div className="screen-body" style={{ paddingBottom: 28 }}>
        <p className="screen-lead">{TYPE_LABEL[room.roomType]} 모임 · 성격과 지역은 수정할 수 없어요.</p>
        <RoomFormFields form={form} onChange={setForm} roomType={room.roomType} onOpenGamePicker={() => setGamePickerOpen(true)} today={today} />
      </div>
      <div className="stickybar">
        <button className="btn cta" disabled={submitting} type="submit">{submitting ? '저장 중…' : '수정 저장'}</button>
      </div>
      <GamePickerDialog isOpen={gamePickerOpen} selectedGameId={form.gameId} allowClear={room.roomType === 'PERSON_FOCUSED'} onSelect={(game) => setForm((current) => ({ ...current, gameId: game.id, selectedGame: game }))} onClear={() => setForm((current) => ({ ...current, gameId: '', selectedGame: null }))} onClose={() => setGamePickerOpen(false)} />
    </form>
  );
}

function EditView({ sessionId, onSave, onBack, dataVersion, today }) {
  const { data, loading, error, retry } = useRequest(
    async (signal) => normalizeRoom(await api.getRoom(sessionId, signal)),
    [sessionId, dataVersion]
  );
  if (error) {
    return (
      <div className="screen sub">
        <TopBar onBack={onBack} />
        <div className="screen-body pad-bottom"><ErrorBox title="모임을 불러오지 못했어요" message={error} onRetry={retry} /></div>
      </div>
    );
  }
  if (loading && !data) {
    return (
      <div className="screen sub">
        <TopBar onBack={onBack} />
        <div className="screen-body pad-bottom"><RoomSkeletons count={1} /></div>
      </div>
    );
  }
  if (!data) {
    return (
      <div className="screen sub">
        <TopBar onBack={onBack} />
        <div className="screen-body pad-bottom"><StateBlock title="지금은 이 모임을 수정할 수 없어요" /></div>
      </div>
    );
  }
  return <EditSessionForm key={data.id} room={data} onSave={onSave} onBack={onBack} today={today} />;
}

export function MyRoomsSection({ myTab, onMyTabChange, dataVersion, onCancelApply, onBack }) {
  const joined = usePaginatedRequest(
    (page, signal) => api.getMyRooms({ role: 'joined', page, size: ROOM_LIST_PAGE_SIZE }, signal),
    [dataVersion]
  );
  const hosted = usePaginatedRequest(
    (page, signal) => api.getMyRooms({ role: 'hosted', page, size: ROOM_LIST_PAGE_SIZE }, signal),
    [dataVersion]
  );
  const tab = myTab === 'hosted' ? 'hosted' : 'joined';
  const page = tab === 'hosted' ? hosted : joined;
  const list = (page.data?.content || []).map(normalizeRoom);
  return (
    <div className="screen sub">
      <TopBar onBack={onBack} title="내 모임" action={<a className="icon-btn" href="#/chats" aria-label="전체 채팅"><ChatIcon /></a>} />
      <div className="screen-body pad-bottom">
        <div className="tabline">
          <button type="button" aria-pressed={tab === 'joined'} className={tab === 'joined' ? 'on' : ''} onClick={() => onMyTabChange('joined')}>참가한</button>
          <button type="button" aria-pressed={tab === 'hosted'} className={tab === 'hosted' ? 'on' : ''} onClick={() => onMyTabChange('hosted')}>개설한</button>
        </div>
        <div style={{ marginTop: 22 }}>
          {page.error && <ErrorBox title="내 모임을 불러오지 못했어요" message={page.error} onRetry={page.retry} />}
          {!page.error && page.loading && !page.data && <RoomSkeletons count={2} />}
          {!page.error && !!list.length && (
            <div className="roomlist">
              {list.map((room) => (
                <MyRoomListItem key={room.id} room={room} tab={tab} onCancelApply={onCancelApply} />
              ))}
            </div>
          )}
          {!page.error && !page.loading && !list.length && (
            <StateBlock
              title={tab === 'joined' ? '참가한 모임이 없어요' : '개설한 모임이 없어요'}
              description={tab === 'joined' ? '마음에 드는 모임을 찾아 한 자리 맡아보세요.' : '직접 모임을 열어 사람을 모아보세요.'}
            >
              <a className="btn" href={tab === 'joined' ? '#/find' : '#/create'}>{tab === 'joined' ? '모임 찾아보기' : '모임 만들기'}</a>
            </StateBlock>
          )}
          {!page.error && !!list.length && <Pagination page={page.data?.page ?? 0} totalPages={page.data?.totalPages ?? 0} loading={page.loading} onChange={page.setPage} />}
        </div>
      </div>
    </div>
  );
}

/** 내 모임 목록 행. 참가 중이며 취소 가능한 모임만 카드 아래 취소 버튼을 함께 보여준다. */
function MyRoomListItem({ room, tab, onCancelApply }) {
  const [pending, setPending] = useState(false);
  const status = sessionStatus(room);
  const cancelable = isJoined(room) && (status === 'RECRUITING' || status === 'CLOSED') && !hasStarted(room);
  const canEnterChat = Boolean(room.myRole && (status === 'RECRUITING' || status === 'CLOSED'));
  const cancel = async () => {
    setPending(true);
    try {
      await onCancelApply(room.id);
    } finally {
      setPending(false);
    }
  };
  const actions = (canEnterChat || cancelable) ? (
    <>
      {canEnterChat && <a className="btn fill sm" href={'#/chat/' + room.id}>채팅</a>}
      {cancelable && <button className="btn fill sm" type="button" disabled={pending} onClick={cancel}>{pending ? '처리 중…' : '참가 취소'}</button>}
      {tab === 'hosted' && <a className="btn fill sm" href={'#/session/' + room.id}>참가자 관리</a>}
    </>
  ) : null;
  return <RoomListItem room={room} showHostBadge={tab === 'hosted'} actions={actions} />;
}

const CHAT_GROUP_WINDOW_MS = 3 * 60 * 1000;

function formatChatDay(isoDate) {
  if (!isoDate) return '';
  if (isoDate === todayInSeoul()) return '오늘';
  const parts = dateParts(isoDate);
  if (!parts) return '';
  const weekday = new Date(Date.UTC(parts.year, parts.monthIndex, parts.day)).getUTCDay();
  return (parts.monthIndex + 1) + '월 ' + parts.day + '일 (' + WEEKDAY_LABELS[weekday] + ')';
}

function formatChatTime(createdAt) {
  const time = timeInSeoul(createdAt);
  return time ? formatRoomTime(time) : '';
}

// 같은 사람이 3분 안에 이어 보낸 메시지는 한 덩어리로 본다. 이름은 덩어리 첫 줄, 시각은 마지막 줄에만 붙는다.
// 입장·퇴장 시스템 메시지는 발신자 개념이 없으므로 사용자 메시지와도, 시스템 메시지끼리도 말풍선으로 묶지 않는다.
function groupChatMessages(messages) {
  const isSystemMessage = (message) => message?.messageType === 'SYSTEM';
  const sameGroup = (left, right) => Boolean(left) && Boolean(right)
    && !isSystemMessage(left) && !isSystemMessage(right)
    && isoDateInSeoul(left.createdAt) === isoDateInSeoul(right.createdAt)
    && Boolean(left.isMine) === Boolean(right.isMine)
    && (left.sender?.nickname || '') === (right.sender?.nickname || '')
    && Math.abs(new Date(right.createdAt) - new Date(left.createdAt)) <= CHAT_GROUP_WINDOW_MS;

  const days = [];
  messages.forEach((message, index) => {
    const day = isoDateInSeoul(message.createdAt);
    let bucket = days[days.length - 1];
    if (!bucket || bucket.day !== day) {
      bucket = { day, rows: [] };
      days.push(bucket);
    }
    bucket.rows.push({
      message,
      isGroupStart: !sameGroup(messages[index - 1], message),
      isGroupEnd: !sameGroup(message, messages[index + 1])
    });
  });
  return days;
}

/** 참가 중이며 채팅에 들어갈 수 있는 모임만 골라 채팅방 목록으로 보여준다. */
// CHAT-08: 목록 화면에 머무는 동안 다른 참가자의 메시지가 커밋되면 이 최소 신호를 받아 재조회를 촉진한다.
// 신호 payload({ roomId, messageId })는 표시 데이터의 정본이 아니므로 값 자체를 화면에 반영하지 않는다.
function isChatRoomUpdatedEvent(payload) {
  return Boolean(payload) && payload.roomId !== undefined && payload.messageId !== undefined;
}

function useChatListRealtimeRefresh() {
  const [realtimeVersion, setRealtimeVersion] = useState(0);

  useEffect(() => {
    let active = true;
    let socket;
    let reconnectTimer;
    let stableConnectionTimer;
    let reconnectAttempts = 0;
    let hasConnectedBefore = false;

    const connect = () => {
      if (!active) return;
      let currentSocket;
      try {
        currentSocket = api.openChatListWebSocket();
        socket = currentSocket;
      } catch {
        return;
      }
      currentSocket.onopen = () => {
        if (!active) return;
        // 최초 연결은 재조회를 촉진하지 않는다. useRequest가 마운트 시 이미 최신 값을 가져온다.
        if (hasConnectedBefore) setRealtimeVersion((version) => version + 1);
        hasConnectedBefore = true;
        stableConnectionTimer = setTimeout(() => { reconnectAttempts = 0; }, 10000);
      };
      currentSocket.onmessage = (event) => {
        if (!active) return;
        let payload;
        try {
          payload = JSON.parse(event.data);
        } catch {
          return;
        }
        if (!isChatRoomUpdatedEvent(payload)) return;
        setRealtimeVersion((version) => version + 1);
      };
      currentSocket.onclose = () => {
        if (!active) return;
        clearTimeout(stableConnectionTimer);
        if (reconnectAttempts >= CHAT_RECONNECT_LIMIT) return;
        const delay = CHAT_RECONNECT_DELAYS[reconnectAttempts] || CHAT_RECONNECT_DELAYS.at(-1);
        reconnectAttempts += 1;
        reconnectTimer = setTimeout(connect, delay);
      };
    };

    connect();
    return () => {
      active = false;
      clearTimeout(reconnectTimer);
      clearTimeout(stableConnectionTimer);
      socket?.close();
    };
  }, []);

  return realtimeVersion;
}

export function ChatListView({ dataVersion, onBack }) {
  const realtimeVersion = useChatListRealtimeRefresh();
  const joined = useRequest((signal) => api.getMyRooms({ role: 'joined', page: 0, size: 100 }, signal), [dataVersion, realtimeVersion]);
  const hosted = useRequest((signal) => api.getMyRooms({ role: 'hosted', page: 0, size: 100 }, signal), [dataVersion, realtimeVersion]);
  const loading = joined.loading || hosted.loading;
  const error = joined.error || hosted.error;
  const rooms = [...(joined.data?.content || []), ...(hosted.data?.content || [])].map(normalizeRoom);
  const seen = new Set();
  const list = rooms
    .filter((room) => Boolean(room.myRole) && (room.status === 'RECRUITING' || room.status === 'CLOSED'))
    .filter((room) => (seen.has(room.id) ? false : (seen.add(room.id), true)))
    .sort((left, right) => new Date(left.startsAt) - new Date(right.startsAt));
  return (
    <div className="screen sub">
      <TopBar onBack={onBack} title="채팅" />
      <div className="screen-body pad-bottom">
        {error && <div style={{ marginTop: 22 }}><ErrorBox title="채팅 목록을 불러오지 못했어요" message={error} onRetry={() => { joined.retry(); hosted.retry(); }} /></div>}
        {!error && loading && !list.length && <div style={{ marginTop: 22 }}><RoomSkeletons count={2} /></div>}
        {!error && !loading && !list.length && (
          <div style={{ marginTop: 22 }}>
            <StateBlock title="참가 중인 채팅이 없어요" description="모임에 참가하면 채팅이 열립니다." />
          </div>
        )}
        {!error && !!list.length && (
          <div className="chatlist">
            {list.map((room) => (
              <a className="chatrow" href={'#/chat/' + room.id} key={room.id}>
                <Cover src={room.game?.imageUrl} />
                <span className="chatrow-copy">
                  <span className="chatrow-head">
                    <strong>{room.title}</strong>
                    <time dateTime={room.startsAt}>{formatStartsAt(room.startsAt)}</time>
                  </span>
                  <span className="chatrow-last">
                    {room.lastMessagePreview || (participantCount(room) + '명 참가 · ' + (room.place || room.region || '장소 미정'))}
                  </span>
                </span>
                {room.unreadCount > 0 && (
                  <span className="badge red" aria-label={room.unreadCount + '개 안읽음'}>
                    {room.unreadCount > 99 ? '99+' : room.unreadCount}
                  </span>
                )}
              </a>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

// 직접 URL로 들어와도 진입 여부는 서버 응답으로만 정해진다. 거절은 서버 원문 대신 계약된 code로 안내한다.
function chatAccessError(error) {
  const message = error instanceof ApiError ? CHAT_ACCESS_MESSAGE[error.code] : undefined;
  return message ? new ApiError({ status: error.status, code: error.code, message }) : error;
}

const CHAT_RECONNECT_LIMIT = 5;
const CHAT_RECONNECT_DELAYS = [500, 1000, 2000, 4000, 8000];
const CHAT_CONNECTION_STATUS = {
  reconnecting: '실시간 채팅을 다시 연결하고 있어요.',
  failed: '실시간 채팅 연결을 시작하지 못했어요. 채팅 목록에서 다시 들어가 주세요.',
  exhausted: '실시간 채팅을 다시 연결하지 못했어요. 채팅 목록에서 다시 들어가 주세요.',
  restricted: '이 채팅의 실시간 연결이 종료됐어요. 채팅 목록에서 다시 들어가 주세요.'
};

function chatStreamMessage(payload, roomId) {
  if (!payload || payload.type !== 'MESSAGE_CREATED' || !payload.message) return null;
  if (String(payload.message.roomId) !== String(roomId) || payload.message.messageId === undefined) return null;
  if (Number(payload.eventId) !== Number(payload.message.messageId)) return null;
  return payload.message;
}

function mergeChatMessages(current, incoming) {
  const byId = new Map(current.map((message) => [String(message.messageId), message]));
  incoming.forEach((message) => byId.set(String(message.messageId), message));
  return [...byId.values()].sort((left, right) => Number(left.messageId) - Number(right.messageId));
}

export function ChatRoomView({ roomId, dataVersion, onBack, onChatRead }) {
  const { data, loading, error } = useRequest(
    (signal) => api.getChatMessages(roomId, signal).catch((cause) => { throw chatAccessError(cause); }),
    [roomId, dataVersion]
  );
  // 상단 바에 띄울 모임 정보. 못 불러와도 대화는 그대로 보여준다.
  const roomInfo = useRequest((signal) => api.getRoom(roomId, signal).catch(() => null), [roomId, dataVersion]);
  const roomIdRef = useRef(roomId);
  const roomGenerationRef = useRef(0);
  const previousRoomIdRef = useRef(roomId);
  if (previousRoomIdRef.current !== roomId) {
    previousRoomIdRef.current = roomId;
    roomGenerationRef.current += 1;
  }
  roomIdRef.current = roomId;
  const [messages, setMessages] = useState([]);
  const [messagesRoomId, setMessagesRoomId] = useState(roomId);
  const [nextBeforeMessageId, setNextBeforeMessageId] = useState(null);
  const [hasNext, setHasNext] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [content, setContent] = useState('');
  const [clientMessageId, setClientMessageId] = useState(createClientMessageId);
  const [clientMessageContent, setClientMessageContent] = useState(null);
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState('');
  const [sendResultUnknown, setSendResultUnknown] = useState(false);
  const [connectionStatus, setConnectionStatus] = useState('');
  const lastEventIdRef = useRef(null);
  const lastMarkedReadMessageIdRef = useRef(0);
  const chatHistoryRef = useRef(null);
  const loadMoreSentinelRef = useRef(null);
  const historyScrollSnapshotRef = useRef(null);
  const historyInitializedRef = useRef(false);
  const isChatAtBottomRef = useRef(true);
  const scrollToBottomRef = useRef(false);
  const composeInputRef = useRef(null);
  const refocusComposeRef = useRef(false);
  const mergeMessages = (incoming) => setMessages((current) => mergeChatMessages(current, incoming));

  useEffect(() => {
    setMessagesRoomId(roomId);
    setMessages([]);
    historyInitializedRef.current = false;
    historyScrollSnapshotRef.current = null;
    isChatAtBottomRef.current = true;
    scrollToBottomRef.current = false;
    setNextBeforeMessageId(null);
    setHasNext(false);
    setLoadingMore(false);
    setSending(false);
    setContent('');
    setClientMessageContent(null);
    setSendError('');
    setSendResultUnknown(false);
    setConnectionStatus('');
    setClientMessageId(createClientMessageId());
    lastMarkedReadMessageIdRef.current = 0;
  }, [roomId]);

  useEffect(() => {
    if (!data) return;
    setMessagesRoomId(roomId);
    const latestMessageId = (data.messages || []).reduce(
      (latest, message) => Math.max(latest, Number(message.messageId) || 0),
      0
    );
    if (latestMessageId > 0) lastEventIdRef.current = latestMessageId;
    mergeMessages(data.messages || []);
    setNextBeforeMessageId(data.nextBeforeMessageId ?? null);
    setHasNext(Boolean(data.hasNext));
  }, [data]);

  // 채팅방에 들어가 최신 메시지를 확인한 시점(초기 로드·실시간 수신 모두 포함)마다 읽음 처리한다.
  useEffect(() => {
    if (messagesRoomId !== roomId || !messages.length) return;
    const latestMessageId = Number(messages.at(-1).messageId) || 0;
    if (latestMessageId <= lastMarkedReadMessageIdRef.current) return;
    const requestedRoomId = roomId;
    lastMarkedReadMessageIdRef.current = latestMessageId;
    api.markChatRead(roomId, latestMessageId)
      .then(() => onChatRead?.())
      .catch(() => {
        if (roomIdRef.current !== requestedRoomId) return;
        if (lastMarkedReadMessageIdRef.current === latestMessageId) lastMarkedReadMessageIdRef.current = 0;
      });
  }, [messages, messagesRoomId, roomId, onChatRead]);

  useEffect(() => {
    if (!data || error) return undefined;
    let active = true;
    let socket;
    let reconnectTimer;
    let stableConnectionTimer;
    let reconnectAttempts = 0;

    const connect = () => {
      if (!active) return;
      let currentSocket;
      let acceptingMessages = true;
      try {
        currentSocket = api.openChatWebSocket(roomId, { afterMessageId: lastEventIdRef.current });
        socket = currentSocket;
      } catch {
        setConnectionStatus('failed');
        return;
      }
      currentSocket.onopen = () => {
        if (!active || !acceptingMessages) return;
        setConnectionStatus('');
        stableConnectionTimer = setTimeout(() => { reconnectAttempts = 0; }, 10000);
      };
      currentSocket.onmessage = (event) => {
        if (!active || !acceptingMessages) return;
        let payload;
        try {
          payload = JSON.parse(event.data);
        } catch {
          acceptingMessages = false;
          currentSocket.close();
          return;
        }
        const message = chatStreamMessage(payload, roomId);
        if (!message) return;
        const eventId = Number(payload.eventId);
        lastEventIdRef.current = Math.max(lastEventIdRef.current || 0, eventId);
        mergeMessages([message]);
      };
      currentSocket.onclose = (event) => {
        if (!active) return;
        acceptingMessages = false;
        clearTimeout(stableConnectionTimer);
        if (event?.code === 1008) {
          setConnectionStatus('restricted');
          return;
        }
        if (reconnectAttempts >= CHAT_RECONNECT_LIMIT) {
          setConnectionStatus('exhausted');
          return;
        }
        const delay = CHAT_RECONNECT_DELAYS[reconnectAttempts] || CHAT_RECONNECT_DELAYS.at(-1);
        reconnectAttempts += 1;
        setConnectionStatus('reconnecting');
        reconnectTimer = setTimeout(connect, delay);
      };
    };

    connect();
    return () => {
      active = false;
      clearTimeout(reconnectTimer);
      clearTimeout(stableConnectionTimer);
      socket?.close();
    };
  }, [data, error, roomId]);

  const loadPreviousMessages = async () => {
    if (!hasNext || loadingMore || nextBeforeMessageId === null) return;
    const requestedRoomId = roomId;
    const requestedGeneration = roomGenerationRef.current;
    setLoadingMore(true);
    try {
      const page = await api.getChatMessages(roomId, { beforeMessageId: nextBeforeMessageId, size: 50 });
      if (roomIdRef.current !== requestedRoomId || roomGenerationRef.current !== requestedGeneration) return;
      const previousMessages = page.messages || [];
      // 응답을 기다리는 동안 도착한 실시간 메시지가 스냅샷을 먼저 소모하지 않도록 prepend 직전에 잡는다.
      // 빈 응답은 목록 길이를 바꾸지 않아 보정 대상이 아니므로 스냅샷도 남기지 않는다.
      if (previousMessages.length && chatHistoryRef.current) {
        historyScrollSnapshotRef.current = {
          scrollHeight: chatHistoryRef.current.scrollHeight,
          scrollTop: chatHistoryRef.current.scrollTop
        };
      }
      mergeMessages(previousMessages);
      setNextBeforeMessageId(page.nextBeforeMessageId ?? null);
      setHasNext(Boolean(page.hasNext));
    } catch (cause) {
      if (roomIdRef.current !== requestedRoomId || roomGenerationRef.current !== requestedGeneration) return;
      setSendError(messageForError(cause, '이전 메시지를 불러오지 못했어요.'));
    } finally {
      if (roomIdRef.current === requestedRoomId && roomGenerationRef.current === requestedGeneration) setLoadingMore(false);
    }
  };

  useEffect(() => {
    if (!loadMoreSentinelRef.current || !globalThis.IntersectionObserver) return undefined;
    const observer = new IntersectionObserver(([entry]) => {
      if (entry.isIntersecting) loadPreviousMessages();
    }, { root: chatHistoryRef.current, rootMargin: '120px 0px 0px', threshold: 0 });
    observer.observe(loadMoreSentinelRef.current);
    return () => observer.disconnect();
  }, [hasNext, loadingMore, nextBeforeMessageId, roomId]);

  const displayedMessages = messagesRoomId === roomId ? messages : [];

  useLayoutEffect(() => {
    const history = chatHistoryRef.current;
    if (!history || !displayedMessages.length) return;
    const snapshot = historyScrollSnapshotRef.current;
    if (snapshot) {
      history.scrollTop = snapshot.scrollTop + history.scrollHeight - snapshot.scrollHeight;
      historyScrollSnapshotRef.current = null;
    } else if (scrollToBottomRef.current || !historyInitializedRef.current || isChatAtBottomRef.current) {
      history.scrollTop = history.scrollHeight;
      scrollToBottomRef.current = false;
      isChatAtBottomRef.current = true;
      historyInitializedRef.current = true;
    }
  }, [displayedMessages.length, roomId]);

  const submit = async (event) => {
    event.preventDefault();
    const trimmed = content.trim();
    if (!trimmed) {
      setSendError('메시지를 입력해주세요.');
      return;
    }
    if ([...trimmed].length > 500) {
      setSendError('메시지는 500자까지 입력할 수 있어요.');
      return;
    }
    refocusComposeRef.current = true;
    // WebSocket 이벤트가 이 HTTP 응답보다 먼저 도착할 수 있으므로 대기 전에 미리 세운다.
    scrollToBottomRef.current = true;
    setSending(true);
    setSendError('');
    setSendResultUnknown(false);
    const messageId = clientMessageContent === null || clientMessageContent === trimmed
      ? clientMessageId
      : createClientMessageId();
    setClientMessageId(messageId);
    setClientMessageContent(trimmed);
    const requestedRoomId = roomId;
    const requestedGeneration = roomGenerationRef.current;
    const requestController = new AbortController();
    let requestStarted = false;
    let deadlineTimer;
    const startRequestDeadline = () => {
      requestStarted = true;
      deadlineTimer = window.setTimeout(
        () => requestController.abort(),
        CHAT_SEND_REQUEST_DEADLINE_MS
      );
    };
    try {
      const saved = await api.sendChatMessage(
        roomId,
        { clientMessageId: messageId, content: trimmed },
        requestController.signal,
        startRequestDeadline
      );
      if (roomIdRef.current !== requestedRoomId || roomGenerationRef.current !== requestedGeneration) return;
      if (requestController.signal.aborted) {
        setSendError(CHAT_SEND_RESULT_UNKNOWN_MESSAGE);
        setSendResultUnknown(true);
        return;
      }
      scrollToBottomRef.current = true;
      mergeMessages([saved]);
      setContent('');
      setClientMessageId(createClientMessageId());
      setClientMessageContent(null);
    } catch (cause) {
      if (roomIdRef.current !== requestedRoomId || roomGenerationRef.current !== requestedGeneration) return;
      if (requestStarted && !(cause instanceof ApiError)) {
        setSendError(CHAT_SEND_RESULT_UNKNOWN_MESSAGE);
        setSendResultUnknown(true);
      } else {
        setSendError(messageForError(cause, '메시지를 보내지 못했어요. 다시 시도해주세요.'));
        setSendResultUnknown(false);
      }
    } finally {
      window.clearTimeout(deadlineTimer);
      if (roomIdRef.current === requestedRoomId && roomGenerationRef.current === requestedGeneration) setSending(false);
    }
  };

  // 전송 중에는 입력이 disabled가 되어 브라우저가 포커스를 뗀다. 전송이 끝나면 되돌려 바로 이어 쓸 수 있게 한다.
  useEffect(() => {
    if (sending || !refocusComposeRef.current) return;
    refocusComposeRef.current = false;
    composeInputRef.current?.focus();
  }, [sending]);

  const handleChatScroll = (event) => {
    const history = event.currentTarget;
    isChatAtBottomRef.current = history.scrollHeight - history.clientHeight - history.scrollTop <= 48;
  };

  const handleComposeKeyDown = (event) => {
    if (event.key !== 'Enter' || event.shiftKey || event.isComposing || event.nativeEvent.isComposing) return;
    event.preventDefault();
    event.currentTarget.form?.requestSubmit();
  };

  const room = roomInfo.data;
  // 이름 색은 참가자 순서를 따른다. 모임 정보를 아직 못 읽었거나 목록에 없는 사람만 이름으로 색을 정한다.
  const participantOrder = new Map((room?.participants || []).map((participant, index) => [participant.nickname, index]));
  const senderColor = (nickname) => (participantOrder.has(nickname) ? playerColor(participantOrder.get(nickname)) : nameColor(nickname));

  return (
    <div className="chat-screen">
      <div className="chat-topbar">
        <button type="button" className="icon-btn" aria-label="뒤로 가기" onClick={onBack}>
          <BackIcon />
        </button>
        <div className="chat-topbar-copy">
          <strong>{room?.title || '모임 채팅'}</strong>
          <span>{room ? participantCount(room) + '명 · ' + formatStartsAt(room.startsAt) : '참가자와 대화 중'}</span>
        </div>
        <a className="icon-btn" href={'#/session/' + roomId} aria-label="모임 상세 보기"><InfoIcon /></a>
      </div>

      {connectionStatus === 'reconnecting' && <p className="sr-only" role="status">{CHAT_CONNECTION_STATUS.reconnecting}</p>}
      {['failed', 'exhausted', 'restricted'].includes(connectionStatus) && (
        <div className="chat-notice" role="status">
          {CHAT_CONNECTION_STATUS[connectionStatus]}
          {' '}
          <a href="#/chats"><b>채팅 목록으로 이동</b></a>
        </div>
      )}

      <div className="chat-log" ref={chatHistoryRef} onScroll={handleChatScroll}>
        {!error && !!displayedMessages.length && <div ref={loadMoreSentinelRef} aria-hidden="true" style={{ height: 1 }} />}
        {error && <ErrorBox title="채팅을 열 수 없어요" message={error} />}
        {!error && loading && !data && <p className="chat-empty" role="status">채팅을 불러오는 중…</p>}
        {!error && loadingMore && <p className="chat-daymark" role="status">이전 메시지를 불러오는 중…</p>}
        {!error && !loading && !displayedMessages.length && <p className="chat-empty">아직 주고받은 메시지가 없어요.<br />모임 전에 인사를 남겨보세요.</p>}
        {groupChatMessages(displayedMessages).map((day) => (
          <React.Fragment key={day.day}>
            <p className="chat-daymark">{formatChatDay(day.day)}</p>
            {day.rows.map(({ message, isGroupStart, isGroupEnd }) => {
              if (message.messageType === 'SYSTEM') {
                return <p className="chat-system" key={message.messageId}>{message.content}</p>;
              }
              const isMine = Boolean(message.isMine);
              const nickname = message.sender?.nickname || '';
              const tone = senderColor(nickname);
              return (
                <div className={'chat-message ' + (isMine ? 'mine' : 'theirs')} data-message-owner={isMine ? 'mine' : 'theirs'} key={message.messageId}>
                  {isGroupStart && !isMine && (
                    <span className="chat-sender">
                      <Avatar name={nickname} color={tone} imageUrl={message.sender?.profileImageUrl} />
                      <b style={{ color: tone }}>{nickname}</b>
                    </span>
                  )}
                  {isGroupStart && isMine && <b className="sr-only">나</b>}
                  <span className="chat-line">
                    <span className="chat-content">{message.content}</span>
                    {isGroupEnd && <time className="chat-time" dateTime={message.createdAt}>{formatChatTime(message.createdAt)}</time>}
                  </span>
                </div>
              );
            })}
          </React.Fragment>
        ))}
      </div>

      {!error && (
        <form className="chat-compose" onSubmit={submit}>
          <label className="sr-only" htmlFor="chat-message">메시지</label>
          <textarea id="chat-message" ref={composeInputRef} disabled={sending} maxLength="500" value={content} onChange={(event) => { setContent(event.target.value); setSendError(''); setSendResultUnknown(false); }} onKeyDown={handleComposeKeyDown} placeholder="메시지 입력" />
          <button className="chat-send" disabled={sending} type="submit" aria-label={sending ? '전송 중…' : sendResultUnknown ? '다시 시도' : '전송'}>
            <SendIcon />
          </button>
        </form>
      )}
      {sendError && (
        <div className="chat-fail" style={{ margin: '0 18px 14px' }} role="alert">
          <span>{sendError}</span>
          {sendResultUnknown && <button type="button" onClick={submit}>재시도</button>}
        </div>
      )}
    </div>
  );
}

export function SocialLinkView({ socialProviders = [], onSocialLink, onBack }) {
  const [linking, setLinking] = useState('');
  // 연결은 제공자 화면으로 전체 페이지를 넘긴다. 성공하면 이 화면이 다시 그려지지 않으므로 상태를 되돌리지 않는다.
  const startLink = async (provider) => {
    setLinking(provider);
    if (!await onSocialLink(provider)) setLinking('');
  };
  return (
    <div className="screen sub">
      <TopBar onBack={onBack} title="소셜 계정 연결" />
      <div className="screen-body pad-bottom">
        <div className="notecard" style={{ marginTop: 12 }}>
          <strong>이메일에 이미 가입된 계정이 있어요</strong>
          <p>이메일만 같다고 자동으로 합치지 않아요. 기존 계정으로 로그인한 지금 상태에서 동의하면 연결됩니다.</p>
        </div>
        <p className="screen-lead" style={{ marginTop: 12 }}>연결하면 다음부터 그 계정으로도 로그인할 수 있어요. 연결 해제는 아직 지원하지 않아요.</p>
        <div className="social-link-list">
          {socialProviders.map((item) => (item.linked
            ? (
              <div className="social-link-row" key={item.provider}>
                <strong>{SOCIAL_PROVIDER_LABEL[item.provider]}</strong>
                <span className="social-link-state linked">연결됨</span>
              </div>
            )
            : (
              <button className="social-link-row" key={item.provider} type="button" disabled={Boolean(linking)} onClick={() => startLink(item.provider)}>
                <strong>{SOCIAL_PROVIDER_LABEL[item.provider]}</strong>
                <span className="social-link-state">{linking === item.provider ? '연결 중…' : '연결하기'}</span>
              </button>
            )))}
          {!socialProviders.length && <p className="screen-lead">지금은 연결할 수 있는 제공자가 없어요.</p>}
        </div>
      </div>
    </div>
  );
}

export function ProfileView({ me, onSave, onLogout, socialProviders = [], onUploadImage, onDeleteImage, dataVersion = 0, unreadCount, chatUnreadCount, onOpenNotifications }) {
  const [nickname, setNickname] = useState(me.nickname);
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);
  const [uploadingImage, setUploadingImage] = useState(false);
  const fileInputRef = useRef(null);
  const counts = useRequest((signal) => Promise.all([
    api.getMyRooms({ role: 'joined', page: 0, size: 1 }, signal),
    api.getMyRooms({ role: 'hosted', page: 0, size: 1 }, signal)
  ]), [dataVersion]);
  const [joinedPage, hostedPage] = counts.data || [];
  useEffect(() => setNickname(me.nickname), [me.nickname]);
  const logout = async () => {
    setLoggingOut(true);
    try {
      await onLogout();
    } finally {
      setLoggingOut(false);
    }
  };
  const submit = async (event) => {
    event.preventDefault();
    setSaving(true);
    try {
      if (await onSave(nickname)) setEditing(false);
    } finally {
      setSaving(false);
    }
  };
  const handleImageSelect = async (event) => {
    const file = event.target.files?.[0];
    if (!file) return;
    if (file.size > 5 * 1024 * 1024) {
      alert('5MB 이하의 이미지를 선택해주세요.');
      return;
    }
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
      alert('JPEG, PNG, WebP 형식의 이미지만 업로드할 수 있어요.');
      return;
    }
    setUploadingImage(true);
    try {
      await onUploadImage(file);
    } finally {
      setUploadingImage(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };
  const handleDeleteImage = async () => {
    if (!confirm('프로필 이미지를 삭제할까요?')) return;
    setUploadingImage(true);
    try {
      await onDeleteImage();
    } finally {
      setUploadingImage(false);
    }
  };
  const stats = [
    { key: '참가한 모임', value: joinedPage?.totalElements ?? '—', href: '#/my/joined' },
    { key: '개설한 모임', value: hostedPage?.totalElements ?? '—', href: '#/my/hosted' }
  ];

  return (
    <div className="screen">
      <div className="screen-body pad-top pad-bottom">
        <ScreenTitle actions={<HeaderActions unreadCount={unreadCount} chatUnreadCount={chatUnreadCount} onOpenNotifications={onOpenNotifications} />}>내정보</ScreenTitle>
        <div className="profile-head">
          <div className="profile-avatar">
            <Avatar name={me.nickname} index={0} imageUrl={me.profileImageUrl} />
            <button className="profile-avatar-edit" type="button" disabled={uploadingImage} aria-label="프로필 사진 변경" onClick={() => fileInputRef.current?.click()}>
              <CameraIcon />
            </button>
            <input ref={fileInputRef} type="file" accept="image/jpeg,image/png,image/webp" style={{ display: 'none' }} onChange={handleImageSelect} aria-label="프로필 사진 파일" />
          </div>
          <div className="profile-name">
            <strong>{me.nickname}</strong>
            {/* 내 정보 응답에 이메일이 없으면 빈 줄을 만들지 않는다. */}
            {me.email && <span>{me.email}</span>}
          </div>
          <button type="button" className="profile-edit-btn" aria-expanded={editing} onClick={() => { setNickname(me.nickname); setEditing(!editing); }}>수정</button>
        </div>

        {editing && (
          <form onSubmit={submit} aria-label="프로필 수정">
            <div className="field">
              <label className="field-label" htmlFor="profile-nickname">닉네임</label>
              <input id="profile-nickname" className="field-input" maxLength="50" autoFocus value={nickname} onChange={(event) => setNickname(event.target.value)} />
            </div>
            <div className="btn-row" style={{ marginTop: 12 }}>
              <button className="btn fill" type="button" disabled={saving} onClick={() => { setNickname(me.nickname); setEditing(false); }}>취소</button>
              <button className="btn" disabled={saving} type="submit">{saving ? '저장 중…' : '저장'}</button>
            </div>
            {me.profileImageUrl && (
              <button className="btn fill sm" type="button" style={{ marginTop: 8 }} disabled={uploadingImage} onClick={handleDeleteImage}>프로필 사진 삭제</button>
            )}
          </form>
        )}

        <dl className="profile-stats">
          {stats.map((stat) => {
            const Tag = stat.href ? 'a' : 'div';
            return <Tag key={stat.key} href={stat.href}><dd>{stat.value}</dd><dt>{stat.key}</dt></Tag>;
          })}
        </dl>

        <div className="divider" style={{ marginTop: 28 }} />
        <div className="menu-list">
          <a className="menu-row" href="#/my"><span className="menu-row-label">내 모임</span><span className="rowarrow"><ArrowIcon size={16} /></span></a>
          <a className="menu-row" href="#/game-list/played"><span className="menu-row-label">해 본 게임</span><span className="rowarrow"><ArrowIcon size={16} /></span></a>
          {socialProviders.length > 0 && (
            <a className="menu-row" href="#/social-link">
              <span className="menu-row-label">소셜 계정 연결</span>
              <span className="menu-row-value">{socialProviders.filter((item) => item.linked).length}개 연결됨</span>
              <span className="rowarrow"><ArrowIcon size={16} /></span>
            </a>
          )}
        </div>
        <div className="divider" style={{ marginTop: 14 }} />

        <button className="btn fill" type="button" style={{ marginTop: 30 }} disabled={loggingOut} onClick={logout}>{loggingOut ? '로그아웃 중…' : '로그아웃'}</button>
        <BggAttribution logoSrc={poweredByBgg} />
      </div>
    </div>
  );
}

// 브라우저 minLength와 서버의 Unicode 문자 수 계산이 다를 수 있어 하한도 같은 기준으로 판정한다.
// 이 문장이 가입이 막힌 사유를 알리는 유일한 자리다. 같은 말을 오류 상자에 겹쳐 띄우지 않는다.
function signupPasswordError(password) {
  const codePointLength = [...password].length;
  if (password && codePointLength < PASSWORD_MIN_CODE_POINTS) {
    return PASSWORD_MIN_CODE_POINTS + '자 이상 입력해야 회원가입을 진행할 수 있어요.';
  }
  if (codePointLength > PASSWORD_MAX_CODE_POINTS) {
    return PASSWORD_MAX_CODE_POINTS + '자를 넘어 회원가입을 진행할 수 없어요. 조금 줄여주세요.';
  }
  if (new TextEncoder().encode(password).length > PASSWORD_MAX_UTF8_BYTES) {
    return '비밀번호가 너무 길어 회원가입을 진행할 수 없어요. 한글이나 이모지는 영문보다 길이를 많이 차지해요.';
  }
  return '';
}

export function AuthView({ onLogin, socialProviders = [], onSocialLogin, onBack }) {
  const [showEmailForm, setShowEmailForm] = useState(false);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const submit = async (event) => {
    event.preventDefault();
    setSubmitting(true);
    setError('');
    try {
      await onLogin({ email, password });
    } catch (requestError) {
      setError(messageForError(requestError));
    } finally {
      setSubmitting(false);
    }
  };
  return (
    <div className="screen">
      {onBack && <TopBar onBack={onBack} />}
      <div className="auth">
        <BrandMark size={62} />
        <h1>오늘 열린 모임에<br />한 자리 맡아두세요</h1>
        <p className="auth-lead">근처에서 열리는 보드게임 모임을 찾고,<br />직접 모임을 열어 사람을 모아요.</p>

        {showEmailForm
          ? (
            <form onSubmit={submit}>
              <div className="field">
                <label className="sr-only" htmlFor="auth-email">이메일</label>
                <input id="auth-email" className="field-input" type="email" autoComplete="email" placeholder="이메일" required value={email} onChange={(event) => setEmail(event.target.value)} />
              </div>
              <div className="field auth-password">
                <label className="sr-only" htmlFor="auth-password">비밀번호</label>
                <input id="auth-password" className="field-input" type={showPassword ? 'text' : 'password'} autoComplete="current-password" placeholder="비밀번호" required value={password} onChange={(event) => setPassword(event.target.value)} />
                <button type="button" onClick={() => setShowPassword((visible) => !visible)} aria-label={showPassword ? '비밀번호 숨기기' : '비밀번호 보기'}>{showPassword ? <EyeOffIcon /> : <EyeIcon />}</button>
              </div>
              {error && <p className="field-hint warn" role="alert">{error}</p>}
              <button className="btn cta" disabled={submitting} type="submit">{submitting ? '처리 중…' : '로그인'}</button>
            </form>
          )
          : <button className="btn cta" type="button" onClick={() => setShowEmailForm(true)}>이메일로 로그인</button>}

        {socialProviders.length > 0 && (
          <>
            <div className="auth-divider"><span>간편 로그인</span></div>
            <div className="social-auth">
              {socialProviders.map((item) => (
                <button className={'social-auth-btn ' + item.provider.toLowerCase()} key={item.provider} type="button" onClick={() => onSocialLogin(item.provider)} aria-label={SOCIAL_PROVIDER_LABEL[item.provider] + '로 계속하기'}>
                  {SOCIAL_PROVIDER_ICON[item.provider]}
                </button>
              ))}
            </div>
          </>
        )}
        <a className="auth-switch" href="#/signup">계정이 없으신가요? <b>회원가입</b></a>
      </div>
    </div>
  );
}

export function SignupView({ onSignup, onBack }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [nickname, setNickname] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const passwordRef = useRef(null);
  const passwordError = signupPasswordError(password);
  const submit = async (event) => {
    event.preventDefault();
    // 사유는 입력란 아래 안내 한 곳에서만 말한다. 여기서는 고칠 자리로 보내기만 한다.
    if (passwordError) {
      passwordRef.current?.focus();
      return;
    }
    setSubmitting(true);
    setError('');
    try {
      const created = await onSignup({ email, password, nickname });
      if (created) window.location.hash = '#/auth';
    } catch (requestError) {
      setError(messageForError(requestError));
    } finally {
      setSubmitting(false);
    }
  };
  return (
    <form className="screen sub" onSubmit={submit}>
      <TopBar onBack={onBack} title="회원가입" />
      <div className="screen-body pad-bottom">
        <div className="field">
          <label className="sr-only" htmlFor="signup-email">이메일</label>
          <input id="signup-email" className="field-input" type="email" autoComplete="email" placeholder="이메일" required value={email} onChange={(event) => setEmail(event.target.value)} />
        </div>
        <div className="field">
          <label className="sr-only" htmlFor="signup-nickname">닉네임</label>
          <input id="signup-nickname" className="field-input" maxLength="50" placeholder="닉네임" required value={nickname} onChange={(event) => setNickname(event.target.value)} />
        </div>
        <div className="field auth-password">
          <label className="sr-only" htmlFor="signup-password">비밀번호</label>
          <input id="signup-password" className="field-input" ref={passwordRef} type={showPassword ? 'text' : 'password'} autoComplete="new-password" minLength={PASSWORD_MIN_CODE_POINTS} placeholder="비밀번호" required value={password} onChange={(event) => setPassword(event.target.value)} aria-describedby="signup-password-hint" aria-invalid={passwordError ? true : undefined} />
          <button type="button" onClick={() => setShowPassword((visible) => !visible)} aria-label={showPassword ? '비밀번호 숨기기' : '비밀번호 보기'}>{showPassword ? <EyeOffIcon /> : <EyeIcon />}</button>
        </div>
        <p id="signup-password-hint" className={passwordError ? 'field-hint warn' : 'field-hint'} role={passwordError ? 'alert' : undefined}>{passwordError || '15자 이상, Unicode와 공백을 사용할 수 있어요.'}</p>
        {error && <p className="field-hint warn" role="alert">{error}</p>}
        <a className="auth-switch" href="#/auth">이미 계정이 있으신가요? <b>로그인</b></a>
      </div>
      <div className="stickybar">
        <button className="btn cta" disabled={submitting} type="submit">{submitting ? '처리 중…' : '회원가입'}</button>
      </div>
    </form>
  );
}

function Splash() {
  return (
    <div className="splash" role="status" aria-label="알밤메이트를 여는 중">
      <BrandMark size={76} />
      <span>알밤메이트</span>
    </div>
  );
}

export function App() {
  const [{ route, arg }, navigate] = useHashRoute();
  const today = useSeoulToday();
  const [me, setMe] = useState(null);
  const [sessionChecked, setSessionChecked] = useState(false);
  const [splashDone, setSplashDone] = useState(false);
  const [gameQuery, setGameQuery] = useState('');
  const [roomQuery, setRoomQuery] = useState('');
  const [roomType, setRoomType] = useState('');
  const [roomFilters, setRoomFilters] = useState(EMPTY_ROOM_FILTERS);
  const [myTab, setMyTab] = useState('joined');
  const [createMode, setCreateMode] = useState('GAME_FOCUSED');
  const [createGame, setCreateGame] = useState(null);
  const [dataVersion, setDataVersion] = useState(0);
  const [socialProviders, setSocialProviders] = useState([]);
  const [toast, setToast] = useState({ message: '', type: '' });
  const authenticated = Boolean(me);
  const toastTimer = useRef(null);
  const meRef = useRef(null);

  useEffect(() => {
    meRef.current = me;
  }, [me]);

  const expireAuthentication = useCallback(() => {
    clearCsrfToken();
    if (!meRef.current) return;
    meRef.current = null;
    setMe(null);
    setDataVersion((version) => version + 1);
    window.location.hash = '#/auth';
  }, []);

  const showToast = useCallback((message, type = '') => {
    setToast({ message, type });
    window.clearTimeout(toastTimer.current);
    toastTimer.current = window.setTimeout(() => setToast({ message: '', type: '' }), 2800);
  }, []);

  const handleNotificationBackgroundError = useCallback((error) => {
    if (!isUnauthenticated(error)) showToast('알림을 새로 확인하지 못했어요.', 'err');
  }, [showToast]);

  const notificationState = useNotificationPolling({
    enabled: Boolean(me),
    panelOpen: route === 'notifications',
    loadNotifications: loadFirstNotificationPage,
    loadUnreadCount: api.getUnreadNotificationCount,
    onBackgroundError: handleNotificationBackgroundError
  });
  const notificationReadSync = useNotificationReadSync({
    enabled: Boolean(me),
    unreadCount: notificationState.unreadCount,
    unreadCountRevision: notificationState.unreadCountRevision,
    readSynchronizationPaused: notificationState.readSynchronizationPaused,
    markNotificationRead: api.markNotificationRead,
    markAllNotificationsRead: api.markAllNotificationsRead,
    replaceNotification: notificationState.replaceNotification,
    refreshUnreadAfterSingleRead: notificationState.refreshUnreadAfterSingleRead,
    pauseForReadSynchronization: notificationState.pauseForReadSynchronization,
    refreshAfterReadSynchronization: notificationState.refreshAfterReadSynchronization,
    resumeAfterReadSynchronization: notificationState.resumeAfterReadSynchronization,
    isUnauthenticated
  });

  // 상단 채팅 아이콘 배지: 알림 배지와 같은 주기로 폴링하고, 방을 읽음 처리한 직후에는 즉시 한 번 더 갱신한다.
  const [chatUnreadVersion, setChatUnreadVersion] = useState(0);
  const refreshChatUnread = useCallback(() => setChatUnreadVersion((version) => version + 1), []);
  useEffect(() => {
    if (!authenticated) return undefined;
    const timer = window.setInterval(refreshChatUnread, NOTIFICATION_POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [authenticated, refreshChatUnread]);
  const chatUnreadSummary = useRequest(
    (signal) => (authenticated ? api.getUnreadChatSummary(signal) : Promise.resolve({ unreadRoomCount: 0 })),
    [authenticated, dataVersion, chatUnreadVersion]
  );
  const chatUnreadCount = chatUnreadSummary.data?.unreadRoomCount || 0;

  const refreshData = () => setDataVersion((version) => version + 1);
  const goBack = () => {
    if (window.history.length > 1) window.history.back();
    else navigate('/home');
  };

  useEffect(() => () => window.clearTimeout(toastTimer.current), []);
  useEffect(() => {
    setUnauthenticatedHandler(expireAuthentication);
    return () => setUnauthenticatedHandler(undefined);
  }, [expireAuthentication]);
  useEffect(() => {
    const timer = window.setTimeout(() => setSplashDone(true), SPLASH_MIN_MS);
    return () => window.clearTimeout(timer);
  }, []);
  useEffect(() => {
    let active = true;
    api.getMyProfile()
      .then((profile) => {
        if (active) setMe(profile);
      })
      .catch((error) => {
        if (error?.name !== 'AbortError' && !isUnauthenticated(error) && active) {
          showToast(messageForError(error, '로그인 상태를 확인하지 못했어요.'), 'err');
        }
      })
      .finally(() => {
        if (active) setSessionChecked(true);
      });
    return () => {
      active = false;
    };
  }, []);
  useEffect(() => {
    const result = consumeSocialAuthResult();
    if (result) showToast(result.message, result.type);
  }, [showToast]);
  // 연결 여부는 요청자 기준으로 계산되므로 로그인 상태가 바뀌면 다시 조회한다.
  useEffect(() => {
    let active = true;
    api.getSocialProviders()
      .then((providers) => {
        if (active) setSocialProviders(providers);
      })
      // 목록을 얻지 못하면 소셜 진입만 감춘다. 이메일 로그인은 그대로 쓸 수 있어 알리지 않는다.
      .catch(() => {
        if (active) setSocialProviders([]);
      });
    return () => {
      active = false;
    };
  }, [authenticated]);
  // #root가 뷰포트에 고정돼 문서는 스크롤되지 않는다. 실제로 스크롤되는 본문을 화면마다 처음으로 되돌린다.
  // 채팅 로그는 최신 메시지에 붙는 자기 규칙이 있으므로 여기서 건드리지 않는다.
  useEffect(() => {
    document.querySelectorAll('.screen-body').forEach((body) => { body.scrollTop = 0; });
  }, [route, arg]);
  useEffect(() => {
    if (route === 'my' && (arg === 'joined' || arg === 'hosted')) setMyTab(arg);
  }, [route, arg]);

  const handleProtectedError = (error, fallback) => {
    if (isUnauthenticated(error)) {
      // 이미 비로그인 상태면 expireAuthentication이 아무 것도 하지 않으므로 여기서 직접 안내한다.
      showToast('로그인이 필요해요.', 'err');
      expireAuthentication();
      return;
    }
    showToast(messageForError(error, fallback), 'err');
  };

  const handleNotificationSelect = (notification) => {
    return selectNotificationAndNavigate({
      notification,
      markAsRead: notificationReadSync.markAsRead,
      getRoom: api.getRoom,
      navigate,
      isUnauthenticated,
      onUnauthenticated: expireAuthentication,
      onUnavailable: (message) => showToast(message, 'err')
    });
  };

  const handleCreateGame = (game) => {
    setCreateGame(game);
    setCreateMode('GAME_FOCUSED');
    navigate('/create');
  };

  const handleLogin = async (credentials) => {
    try {
      const profile = await api.login(credentials);
      meRef.current = profile;
      setMe(profile);
      refreshData();
      showToast('로그인했어요.');
      navigate('/home', { replace: true });
      return true;
    } catch (error) {
      showToast(messageForError(error, '로그인하지 못했어요.'), 'err');
      return false;
    }
  };

  // 로그인·연결 모두 제공자 화면으로 전체 페이지를 넘긴다. state 생성과 리다이렉트는 서버가 담당한다.
  const handleSocialLogin = (provider) => {
    window.location.assign(socialLoginUrl(provider));
  };

  const handleSocialLink = async (provider) => {
    try {
      window.location.assign(await api.startSocialLink(provider));
      return true;
    } catch (error) {
      handleProtectedError(error, '소셜 계정 연결을 시작하지 못했어요.');
      return false;
    }
  };

  const handleSignup = async (credentials) => {
    try {
      await api.signup(credentials);
      showToast('회원가입이 완료됐어요. 로그인해주세요.');
      return true;
    } catch (error) {
      showToast(messageForError(error, '회원가입하지 못했어요.'), 'err');
      return false;
    }
  };

  const handleLogout = async () => {
    try {
      await api.logout();
      meRef.current = null;
      setMe(null);
      refreshData();
      showToast('로그아웃했어요.');
      navigate('/home', { replace: true });
    } catch (error) {
      if (isUnauthenticated(error)) {
        expireAuthentication();
        return;
      }
      showToast(messageForError(error, '로그아웃하지 못했어요.'), 'err');
    }
  };

  const handleCreate = async (form) => {
    if (!me) {
      navigate('/auth');
      showToast('로그인이 필요합니다.', 'err');
      return false;
    }
    const result = validateRoomForm(form, createMode);
    if (result.error) {
      showToast(result.error, 'err');
      return false;
    }
    try {
      const room = await api.createRoom({ roomType: createMode, ...result.room, description: result.room.description || null });
      setCreateGame(null);
      refreshData();
      showToast('모임이 열렸어요! 참가 신청을 받는 중입니다.');
      navigate('/session/' + room.id, { replace: true });
      return true;
    } catch (error) {
      handleProtectedError(error, '모임을 열지 못했어요.');
      return false;
    }
  };

  const handleSave = async (roomId, form, roomType) => {
    const result = validateRoomForm(form, roomType);
    if (result.error) {
      showToast(result.error, 'err');
      return false;
    }
    try {
      await api.updateRoom(roomId, { ...result.room, description: result.room.description || null });
      refreshData();
      showToast('모임 정보를 수정했어요.');
      navigate('/session/' + roomId, { replace: true });
      return true;
    } catch (error) {
      handleProtectedError(error, '모임 정보를 수정하지 못했어요.');
      return false;
    }
  };

  const handleApply = async (roomId) => {
    try {
      await api.participate(roomId);
      refreshData();
      showToast('참가했어요! 내 모임에서 확인할 수 있어요.');
      return true;
    } catch (error) {
      handleProtectedError(error, '참가하지 못했어요.');
      return false;
    }
  };

  const handleCancelApply = async (roomId) => {
    try {
      await api.cancelParticipation(roomId);
      refreshData();
      showToast('참가를 취소했어요.');
      return true;
    } catch (error) {
      handleProtectedError(error, '참가를 취소하지 못했어요.');
      return false;
    }
  };

  // 대기 명령은 성공·실패 모두 방의 행동 가능 여부를 바꾸므로 상세를 다시 읽어야 CTA가 서버 상태로 수렴한다.
  const handleJoinWaitlist = async (roomId) => {
    try {
      const result = await api.joinWaitlist(roomId);
      showToast(result?.position ? '대기 신청했어요. 현재 ' + result.position + '번째예요.' : '대기 신청했어요.');
      return true;
    } catch (error) {
      handleProtectedError(error, '대기 신청하지 못했어요.');
      return false;
    } finally {
      refreshData();
    }
  };

  const handleCancelWaitlist = async (roomId) => {
    try {
      await api.cancelWaitlist(roomId);
      showToast('대기를 취소했어요.');
      return true;
    } catch (error) {
      handleProtectedError(error, '대기를 취소하지 못했어요.');
      return false;
    } finally {
      refreshData();
    }
  };

  const handleHostCancel = async (roomId) => {
    try {
      await api.cancelRoom(roomId);
      refreshData();
      showToast('모임을 취소했어요.');
      return true;
    } catch (error) {
      handleProtectedError(error, '모임을 취소하지 못했어요.');
      return false;
    }
  };

  const handleFinish = async (roomId) => {
    try {
      await api.finishRoom(roomId);
      refreshData();
      showToast('모임을 종료했어요.');
      return true;
    } catch (error) {
      handleProtectedError(error, '모임을 종료하지 못했어요.');
      return false;
    }
  };

  const handleSaveProfile = async (nickname) => {
    if (!nickname.trim()) {
      showToast('닉네임을 입력해주세요.', 'err');
      return false;
    }
    try {
      const profile = await api.updateMyProfile({ nickname });
      setMe(profile);
      refreshData();
      showToast('닉네임을 저장했어요.');
      return true;
    } catch (error) {
      handleProtectedError(error, '닉네임을 저장하지 못했어요.');
      return false;
    }
  };

  const handleUploadProfileImage = async (file) => {
    try {
      const profile = await api.uploadProfileImage(file);
      setMe(profile);
      showToast('프로필 이미지를 변경했어요.');
      return true;
    } catch (error) {
      handleProtectedError(error, '프로필 이미지를 변경하지 못했어요.');
      return false;
    }
  };

  const handleDeleteProfileImage = async () => {
    try {
      const profile = await api.deleteProfileImage();
      setMe(profile);
      showToast('프로필 이미지를 삭제했어요.');
      return true;
    } catch (error) {
      handleProtectedError(error, '프로필 이미지를 삭제하지 못했어요.');
      return false;
    }
  };

  const unreadCount = notificationReadSync.visibleUnreadCount;
  const openNotifications = () => navigate('/notifications');
  const headerActions = <HeaderActions unreadCount={unreadCount} chatUnreadCount={chatUnreadCount} onOpenNotifications={openNotifications} />;

  let content;
  if (route === 'find') {
    content = <FindRoomsView roomType={roomType} onRoomTypeChange={setRoomType} roomQuery={roomQuery} onRoomQueryChange={setRoomQuery} roomFilters={roomFilters} onRoomFiltersChange={setRoomFilters} dataVersion={dataVersion} />;
  } else if (route === 'game-list' && arg === 'played') {
    // GamesView는 initialFilters를 최초 마운트에만 반영하므로, 두 목록 route에 다른 key를 줘
    // 전환 때 재마운트시킨다. 같은 route 안에서 고른 필터는 key가 그대로라 유지된다.
    content = me
      ? <div className="screen sub" key="game-list-played"><GamesView title="해 본 게임" gameQuery={gameQuery} onGameQueryChange={setGameQuery} dataVersion={dataVersion} onPlayedError={handleProtectedError} headerActions={headerActions} initialFilters={{ ...EMPTY_GAME_FILTERS, playedFilter: 'PLAYED_ONLY' }} onBack={goBack} /></div>
      : <LoginRequiredView message="해 본 게임을 보려면 로그인해주세요." onBack={goBack} />;
  } else if (route === 'game-list') {
    content = <div className="screen" key="game-list"><GamesView title="게임 찾기" gameQuery={gameQuery} onGameQueryChange={setGameQuery} dataVersion={dataVersion} onPlayedError={handleProtectedError} headerActions={headerActions} /></div>;
  } else if (route === 'game-rankings') {
    content = <GameRankingView onBack={goBack} dataVersion={dataVersion} />;
  } else if (route === 'game') {
    content = <GameDetailView gameId={arg} onCreateGame={handleCreateGame} onBack={goBack} dataVersion={dataVersion} onPlayedError={handleProtectedError} renderRoom={(room) => <RoomListItem key={room.id} room={room} />} />;
  } else if (route === 'session') {
    content = <SessionDetailView sessionId={arg} me={me} onBack={goBack} onApply={handleApply} onCancelApply={handleCancelApply} onHostCancel={handleHostCancel} onFinish={handleFinish} onJoinWaitlist={handleJoinWaitlist} onCancelWaitlist={handleCancelWaitlist} onWaitlistSettled={refreshData} dataVersion={dataVersion} />;
  } else if (route === 'create') {
    content = me
      ? <CreateView createMode={createMode} onCreateModeChange={setCreateMode} initialGame={createGame} onCreate={handleCreate} onBack={goBack} today={today} />
      : <LoginRequiredView message="모임을 만들려면 로그인해주세요." onBack={goBack} />;
  } else if (route === 'edit') {
    content = me
      ? <EditView sessionId={arg} onSave={handleSave} onBack={goBack} dataVersion={dataVersion} today={today} />
      : <LoginRequiredView message="모임을 수정하려면 로그인해주세요." onBack={goBack} />;
  } else if (route === 'my') {
    content = me
      ? <MyRoomsSection myTab={myTab} onMyTabChange={setMyTab} dataVersion={dataVersion} onCancelApply={handleCancelApply} onBack={goBack} />
      : <LoginRequiredView message="내 모임을 보려면 로그인해주세요." onBack={goBack} />;
  } else if (route === 'chat') {
    content = me
      ? <ChatRoomView roomId={arg} dataVersion={dataVersion} onBack={goBack} onChatRead={refreshChatUnread} />
      : <LoginRequiredView message="모임 채팅을 보려면 로그인해주세요." onBack={goBack} />;
  } else if (route === 'chats') {
    content = me
      ? <ChatListView dataVersion={dataVersion} onBack={goBack} />
      : <LoginRequiredView message="채팅 목록을 보려면 로그인해주세요." onBack={goBack} />;
  } else if (route === 'notifications') {
    content = me
      ? (
        <NotificationPanel
          notifications={notificationState.notifications}
          listStatus={notificationState.listStatus}
          optimisticReadIds={notificationReadSync.optimisticReadIds}
          canMarkAllAsRead={unreadCount > 0}
          bulkReadPending={notificationReadSync.bulkReadPending}
          synchronizationFailed={notificationReadSync.synchronizationFailed}
          onBack={goBack}
          onRetry={notificationState.retry}
          onSelectNotification={handleNotificationSelect}
          onMarkAllAsRead={notificationReadSync.markAllAsRead}
          onRetrySynchronization={notificationReadSync.retrySynchronization}
        />
      )
      : <LoginRequiredView message="알림을 보려면 로그인해주세요." onBack={goBack} />;
  } else if (route === 'bot') {
    content = me
      ? <BotView onBack={goBack} onToast={showToast} />
      : <LoginRequiredView message="알밤봇을 쓰려면 로그인해주세요." onBack={goBack} />;
  } else if (route === 'match') {
    // 진행 단계는 서버가 없어 저절로 바뀌지 않으므로 주소로 확인한다(#/match/searching 등).
    content = me
      ? <MatchView phase={arg} dataVersion={dataVersion} onBack={goBack} onNavigate={navigate} onToast={showToast} />
      : <LoginRequiredView message="온라인 매칭을 쓰려면 로그인해주세요." onBack={goBack} />;
  } else if (route === 'online-room') {
    content = me
      ? <OnlineRoomView dataVersion={dataVersion} onBack={goBack} onToast={showToast} />
      : <LoginRequiredView message="온라인 방에 들어가려면 로그인해주세요." onBack={goBack} />;
  } else if (route === 'social-link') {
    content = me
      ? <SocialLinkView socialProviders={socialProviders} onSocialLink={handleSocialLink} onBack={goBack} />
      : <LoginRequiredView message="소셜 계정을 연결하려면 로그인해주세요." onBack={goBack} />;
  } else if (route === 'profile') {
    content = me
      ? <ProfileView me={me} onSave={handleSaveProfile} onLogout={handleLogout} socialProviders={socialProviders} onUploadImage={handleUploadProfileImage} onDeleteImage={handleDeleteProfileImage} dataVersion={dataVersion} unreadCount={unreadCount} chatUnreadCount={chatUnreadCount} onOpenNotifications={openNotifications} />
      : <AuthView onLogin={handleLogin} socialProviders={socialProviders} onSocialLogin={handleSocialLogin} />;
  } else if (route === 'auth') {
    content = me
      ? <div className="screen sub"><TopBar onBack={goBack} /><div className="screen-body pad-bottom"><StateBlock title="이미 로그인되어 있어요" description="홈에서 모임을 찾아보세요."><a className="btn" href="#/home">홈으로 이동</a></StateBlock></div></div>
      : <AuthView onLogin={handleLogin} socialProviders={socialProviders} onSocialLogin={handleSocialLogin} onBack={goBack} />;
  } else if (route === 'signup') {
    content = me
      ? <div className="screen sub"><TopBar onBack={goBack} /><div className="screen-body pad-bottom"><StateBlock title="이미 로그인되어 있어요" description="홈에서 모임을 찾아보세요."><a className="btn" href="#/home">홈으로 이동</a></StateBlock></div></div>
      : <SignupView onSignup={handleSignup} onBack={goBack} />;
  } else {
    content = <HomeView me={me} unreadCount={unreadCount} chatUnreadCount={chatUnreadCount} onOpenNotifications={openNotifications} dataVersion={dataVersion} />;
  }

  // 상단 화면에서만 탭바를 띄운다. 하위 화면은 뒤로가기로 돌아간다.
  const showTabs = ROOT_ROUTES.includes(route) || !SUB_ROUTES.includes(route);

  return (
    <>
      {(!sessionChecked || !splashDone) && <Splash />}
      {content}
      {/* P2 시안. 하단 탭이 보이는 상단 화면에서만 띄운다. */}
      {showTabs && <a className="bot-fab" href="#/bot" aria-label="알밤봇 열기"><BrandMark size={30} tone="#fff" hole="#0A0A0A" /></a>}
      {showTabs && <MobileBottomNavigation route={route} authenticated={authenticated} />}
      <div id="toast" role="status" aria-live="polite" className={(toast.message ? 'show ' : '') + (toast.type === 'err' ? 'err' : '')}>{toast.message}</div>
    </>
  );
}

const rootElement = document.getElementById('root');
if (rootElement) createRoot(rootElement).render(<App />);
