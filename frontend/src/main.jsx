import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import brandSymbol from '../assets/albam-mate-symbol.png';
import poweredByBgg from '../assets/powered-by-bgg.svg';
import { ApiError, api, clearCsrfToken, messageForError, setUnauthenticatedHandler, socialLoginUrl } from './api';
import { isUnauthenticated, usePaginatedRequest, useRequest } from './shared/async';
import { FilterCheckGroup, FilterPanel, FilterRadioGroup } from './shared/filters';
import { ErrorBox, LoadingBox, Pagination, SearchHeader, SectionIcon } from './shared/ui';
import { GameDetailView, GamePickerDialog, GamesView, EMPTY_GAME_FILTERS, ROOM_LIST_PAGE_SIZE, normalizeRoom } from './game';
import { NotificationPanel } from './notification/NotificationPanel';
import { selectNotificationAndNavigate } from './notification/notificationNavigation';
import { useNotificationPolling } from './notification/useNotificationPolling';
import { useNotificationReadSync } from './notification/useNotificationReadSync';
import './styles.css';

const WEEKDAY_LABELS = ['일', '월', '화', '수', '목', '금', '토'];
const EXP_LABEL = {
  ALL_LEVELS: '경험 무관',
  BEGINNER_WELCOME: '초보 환영',
  EXPERIENCED_PREFERRED: '경험자 위주'
};
const CAPACITY_OPTIONS = Array.from({ length: 10 }, (_, index) => index + 1);
const HOUR_OPTIONS = Array.from({ length: 12 }, (_, index) => index + 1);
const MINUTE_OPTIONS = Array.from({ length: 6 }, (_, index) => index * 10);
const loadFirstNotificationPage = (signal) => api.getNotifications({ page: 0, size: 10 }, signal);
// 인원 숫자 입력은 마지막 입력 뒤 이 시간이 지나면 조회한다. 체크박스는 기다리지 않는다.
const GAME_NUMBER_FILTER_DEBOUNCE_MS = 400;
export const CHAT_SEND_REQUEST_DEADLINE_MS = 3_000;
export const CHAT_SEND_RESULT_UNKNOWN_MESSAGE = '전송 여부를 확인하지 못했어요. 다시 시도해주세요.';
// 회원가입 비밀번호 한도는 서버 검증 규칙과 같은 값을 쓴다. 한쪽만 바뀌면 안내와 결과가 어긋난다.
const PASSWORD_MIN_CODE_POINTS = 15;
const PASSWORD_MAX_CODE_POINTS = 64;
const PASSWORD_MAX_UTF8_BYTES = 72;
const SOCIAL_PROVIDER_LABEL = { GOOGLE: 'Google', NAVER: 'Naver', KAKAO: 'Kakao' };
function GoogleIcon() {
  return (
    <svg viewBox="0 0 20 20" width="26" height="26" aria-hidden="true">
      <path fill="#4285F4" d="M19.6 10.23c0-.68-.06-1.33-.17-1.96H10v3.71h5.4a4.62 4.62 0 0 1-2 3.03v2.52h3.24c1.9-1.75 2.96-4.33 2.96-7.3z" />
      <path fill="#34A853" d="M10 20c2.7 0 4.96-.89 6.62-2.42l-3.24-2.52c-.9.6-2.05.96-3.38.96-2.6 0-4.8-1.75-5.59-4.11H1.06v2.6A10 10 0 0 0 10 20z" />
      <path fill="#FBBC05" d="M4.41 11.9A5.99 5.99 0 0 1 4.09 10c0-.66.11-1.3.32-1.9V5.5H1.06A9.98 9.98 0 0 0 0 10c0 1.61.39 3.14 1.06 4.5l3.35-2.6z" />
      <path fill="#EA4335" d="M10 3.96c1.47 0 2.79.5 3.83 1.49l2.87-2.87C14.95.98 12.7 0 10 0 6.09 0 2.71 2.24 1.06 5.5l3.35 2.6C5.2 5.71 7.4 3.96 10 3.96z" />
    </svg>
  );
}
function NaverIcon() {
  return (
    <svg viewBox="0 0 20 20" width="35" height="35" aria-hidden="true">
      <rect width="20" height="20" rx="4" fill="#03C75A" />
      <path fill="#fff" d="M11.6 5v5.3L8.4 5H6v10h2.4v-5.3l3.2 5.3H14V5z" />
    </svg>
  );
}
function KakaoIcon() {
  return (
    <svg viewBox="0 0 20 20" width="35" height="35" aria-hidden="true">
      <circle cx="10" cy="10" r="10" fill="#FEE500" />
      <path fill="#391B1B" d="M10 4.8c-3.31 0-6 2.13-6 4.76 0 1.7 1.14 3.2 2.85 4.05-.13.46-.46 1.63-.53 1.88-.08.31.11.31.24.22.1-.07 1.62-1.1 2.28-1.55.37.05.75.08 1.16.08 3.31 0 6-2.13 6-4.76s-2.69-4.68-6-4.68z" />
    </svg>
  );
}
const SOCIAL_PROVIDER_ICON = { GOOGLE: <GoogleIcon />, NAVER: <NaverIcon />, KAKAO: <KakaoIcon /> };
function EyeIcon() {
  return (
    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7z" />
      <circle cx="12" cy="12" r="3" />
    </svg>
  );
}
function EyeOffIcon() {
  return (
    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M17.94 17.94A10.94 10.94 0 0 1 12 19c-7 0-11-7-11-7a18.6 18.6 0 0 1 4.22-5.06M9.9 4.24A10.94 10.94 0 0 1 12 4c7 0 11 7 11 7a18.6 18.6 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" />
      <line x1="1" y1="1" x2="23" y2="23" />
    </svg>
  );
}

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
  'link-required': { message: '같은 이메일을 쓰는 계정이 이미 있어요. 로그인한 뒤 마이페이지에서 연결해주세요.', type: 'err' },
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

function formatCalendarDate(isoDate) {
  const parts = dateParts(isoDate);
  if (!parts) return '';
  const weekday = new Date(Date.UTC(parts.year, parts.monthIndex, parts.day)).getUTCDay();
  return parts.year + '년 ' + (parts.monthIndex + 1) + '월 ' + parts.day + '일 (' + WEEKDAY_LABELS[weekday] + ')';
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

function timeFromParts({ isAfternoon, hour, minute }) {
  return zeroPad((hour === 12 ? 0 : hour) + (isAfternoon ? 12 : 0)) + ':' + zeroPad(minute);
}

function formatRoomTime(time) {
  const parts = timeParts(time);
  return (parts.isAfternoon ? '오후' : '오전') + ' ' + parts.hour + ':' + zeroPad(parts.minute);
}

function monthFromIsoDate(isoDate) {
  const parts = dateParts(isoDate) || dateParts(defaultRoomDate());
  return new Date(parts.year, parts.monthIndex, 1);
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

  const navigate = (path) => {
    window.location.hash = path.startsWith('#') ? path : '#' + path;
  };

  return [location, navigate];
}

function activeParticipantCount(room) {
  return Math.max(0, room.participantCount - 1);
}

function participantCount(room) {
  return room.participantCount;
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

function statusMeta(room) {
  const labels = {
    RECRUITING: ['모집 중', 'green'],
    CLOSED: ['모집 마감', 'amber'],
    CANCELED: ['취소됨', 'red'],
    FINISHED: ['종료됨', 'gray']
  };
  const entry = labels[sessionStatus(room)] || ['상태 확인 중', 'gray'];
  return { code: sessionStatus(room), label: entry[0], className: entry[1] };
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
    recruitmentCapacity: room?.recruitmentCapacity || 4,
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
  if (!Number.isInteger(room.recruitmentCapacity) || room.recruitmentCapacity < 1 || room.recruitmentCapacity > 10) return { error: '모집 정원은 본인 제외 1~10명이어야 해요.' };
  return { room };
}

function Header({ route, me, notificationMenu }) {
  const rootRoute = { find: 'find', game: 'game-list', 'game-list': 'game-list', create: 'profile', edit: 'profile', my: 'profile', chat: 'chats', chats: 'chats', profile: 'profile' };
  const visibleUnreadCount = notificationMenu.unreadCount > 99 ? '99+' : notificationMenu.unreadCount;
  const notificationLabel = notificationMenu.unreadCount > 0
    ? '알림함, 읽지 않은 알림 ' + notificationMenu.unreadCount + '개'
    : '알림함';
  return (
    <header>
      <div className="hwrap">
        <a className="logo" href="#/home" aria-label="알밤메이트 홈">
          <span className="brand-mark" aria-hidden="true"><img src={brandSymbol} alt="" /></span>
          <span className="brand-wordmark"><span className="brand-name">알밤</span><span className="brand-mate">메이트</span></span>
        </a>
        <nav id="gnb" aria-label="주요 메뉴">
          <a href="#/game-list" className={rootRoute[route] === 'game-list' ? 'on' : ''}>게임 찾기</a>
          <a href="#/find" className={rootRoute[route] === 'find' ? 'on' : ''}>모임 찾기</a>
          {me && (
            <a href="#/chats" className={'nav-icon-btn' + (rootRoute[route] === 'chats' ? ' on' : '')} aria-label="채팅">
              <svg viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z" /></svg>
            </a>
          )}
          {me && (
            <div className="notification-menu">
              <button
                type="button"
                className={'nav-icon-btn ' + (notificationMenu.open ? 'on' : '')}
                aria-label={notificationLabel}
                aria-expanded={notificationMenu.open}
                onClick={notificationMenu.onToggle}
              >
                <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" /><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" /></svg>
                {notificationMenu.unreadCount > 0 && (
                  <span className="notification-badge" aria-hidden="true">{visibleUnreadCount}</span>
                )}
              </button>
              <NotificationPanel
                open={notificationMenu.open}
                notifications={notificationMenu.notifications}
                listStatus={notificationMenu.listStatus}
                optimisticReadIds={notificationMenu.optimisticReadIds}
                canMarkAllAsRead={notificationMenu.unreadCount > 0}
                bulkReadPending={notificationMenu.bulkReadPending}
                synchronizationFailed={notificationMenu.synchronizationFailed}
                onClose={notificationMenu.onClose}
                onRetry={notificationMenu.onRetry}
                onSelectNotification={notificationMenu.onSelectNotification}
                onMarkAllAsRead={notificationMenu.onMarkAllAsRead}
                onRetrySynchronization={notificationMenu.onRetrySynchronization}
              />
            </div>
          )}
          {me
            ? <a href="#/profile" className={'nav-icon-btn ' + (rootRoute[route] === 'profile' ? 'on' : '')} aria-label={me.nickname + ' 프로필'}>{me.profileImageUrl ? <img className="gnb-avatar" src={me.profileImageUrl} alt="" /> : <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><circle cx="12" cy="8" r="5" /><path d="M20 21a8 8 0 0 0-16 0" /></svg>}</a>
            : <a href="#/auth" className="btn pill">로그인</a>}
        </nav>
      </div>
    </header>
  );
}

function SiteFooter() {
  return (
    <footer className="site-footer">
      <div className="fwrap">
        <a className="fbrand" href="https://boardgamegeek.com" target="_blank" rel="noreferrer noopener">
          <img src={poweredByBgg} alt="Powered by BGG" />
        </a>
        <p className="fnote">게임 정보는 <a href="https://boardgamegeek.com" target="_blank" rel="noreferrer noopener">BoardGameGeek</a>, 국내 보드게임 자료, 알밤 메이트 팀의 직접 작성·검수와 플레이 경험을 바탕으로 구성했습니다.</p>
      </div>
    </footer>
  );
}

function ScrollToTopButton() {
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    const onScroll = () => setVisible(window.scrollY > 400);
    window.addEventListener('scroll', onScroll);
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  if (!visible) return null;

  return (
    <button
      type="button"
      className="scroll-top-btn"
      aria-label="맨 위로 이동"
      onClick={() => window.scrollTo({ top: 0, behavior: 'smooth' })}
    >
      <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><line x1="12" y1="19" x2="12" y2="5" /><polyline points="5 12 12 5 19 12" /></svg>
    </button>
  );
}

function SeatIcons({ room }) {
  const active = activeParticipantCount(room);
  return (
    <>
      {Array.from({ length: active }, (_, index) => <span className="seat f" key={'filled-' + index} />)}
      {Array.from({ length: Math.max(0, room.recruitmentCapacity - active) }, (_, index) => <span className="seat" key={'empty-' + index} />)}
    </>
  );
}

/** 내 모임 목록 행. 참가 중이며 취소 가능한 모임만 카드 아래 취소 버튼을 함께 보여준다. */
function MyRoomListItem({ room, onCancelApply }) {
  const [pending, setPending] = useState(false);
  const status = sessionStatus(room);
  const cancelable = isJoined(room) && (status === 'RECRUITING' || status === 'CLOSED') && !hasStarted(room);
  const cancel = async () => {
    setPending(true);
    try {
      await onCancelApply(room.id);
    } finally {
      setPending(false);
    }
  };
  return (
    <div className="scard-shell">
      <SessionCard room={room} />
      {cancelable && (
        <div className="scard-actions">
          <button className="btn ghost" disabled={pending} type="button" onClick={cancel}>{pending ? '처리 중…' : '참가 취소'}</button>
        </div>
      )}
    </div>
  );
}

function SessionCard({ room }) {
  const game = room.game;
  const status = statusMeta(room);
  return (
    <a className="scard" href={'#/session/' + room.id}>
      <div className="scard-top">
        <span className="gemoji" aria-hidden="true">{game ? '🎲' : '🙌'}</span>
        <div>
          <div className="stitle">
            {room.title} <span className={'badge ' + (room.roomType === 'PERSON_FOCUSED' ? 'people' : 'game')}>{room.roomType === 'PERSON_FOCUSED' ? '사람 중심' : '게임 중심'}</span>{' '}
            <span className="chip">{EXP_LABEL[room.experienceLevel]}</span>
            {status.code !== 'RECRUITING' && <>{' '}<span className={'badge ' + status.className}>{status.label}</span></>}
          </div>
          {/* 장식 이모지는 낭독기가 "시계"·"압정"으로 읽지 않도록 본문에서 떼어 놓는다. */}
          <div className="smeta">{game ? <><span aria-hidden="true">🎲</span> {game.title}</> : '게임은 모임에서 정해요'}</div>
          <div className="smeta"><span aria-hidden="true">🕐</span> {formatStartsAt(room.startsAt)} · <span aria-hidden="true">📍</span> {room.region || '홍대'}</div>
        </div>
      </div>
      {/* 카드마다 같은 문구가 반복되던 "상세 위치는 참가 후 확인"은 뺐다. 상세 화면에서 안내한다. */}
      <div className="sfoot">
        <span className="cap">총 {participantCount(room)}/{room.recruitmentCapacity + 1}명</span>
        <span>{room.isRulemasterLed ? '룰마스터 진행' : '참가자끼리 진행'}</span>
      </div>
    </a>
  );
}

function LoginRequiredView({ message = '이 기능은 로그인 후 이용할 수 있어요.' }) {
  return <div className="card"><h2>로그인이 필요해요</h2><p className="hint" style={{ marginBottom: 16 }}>{message}</p><a className="btn" href="#/auth">로그인 또는 회원가입</a></div>;
}

function HomeView({ onBrowsePeople, onSearchGame, dataVersion }) {
  // 지금은 게임 이름 검색으로 동작한다. 통합 검색으로 확장할 자리다.
  const [input, setInput] = useState('');
  const { data, loading, error } = useRequest(
    (signal) => api.getRooms({ type: 'PERSON_FOCUSED', page: 0, size: 1 }, signal),
    [dataVersion]
  );
  const personCount = data?.totalElements ?? 0;
  return (
    <section className="card hero">
      <h1>오늘, 보드게임 한 판 어때요? 🎲</h1>
      <p>게임을 먼저 고르거나, 함께할 사람부터 찾아 모임을 만들 수 있어요.</p>
      <form className="inline-search hero-search" onSubmit={(event) => { event.preventDefault(); onSearchGame(input.trim()); }}>
        <label className="hint" htmlFor="home-q" style={{ position: 'absolute', left: -9999 }}>게임 이름 검색</label>
        <input id="home-q" value={input} onChange={(event) => setInput(event.target.value)} placeholder="게임 이름으로 검색" />
        <button type="submit" aria-label="검색"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5" /><line x1="21.5" y1="21.5" x2="15.3" y2="15.3" /></svg></button>
      </form>
      <div className="dual">
        <a className="entry gamefirst" href="#/game-list"><span className="big">🎲</span><h3>게임부터 찾기</h3><p>하고 싶은 게임을 검색하고, 그 게임의 공개 모임을 찾아보세요.</p><span className="sub">게임 2000개 둘러보기 →</span></a>
        <a className="entry peoplefirst" href="#/find" onClick={onBrowsePeople}><span className="big">🙌</span><h3>사람부터 만나기</h3><p>게임이 아직 정해지지 않아도 괜찮아요. 제목으로 원하는 모임을 찾아보세요.</p><span className="sub">{loading ? '공개 모임 불러오는 중…' : '공개 모임 ' + personCount + '개 →'}</span></a>
      </div>
      {error && <p className="hint" style={{ marginTop: 16 }}>공개 모임 수를 불러오지 못했어요: {error}</p>}
    </section>
  );
}

function roomFilterParameters(filters, today) {
  const range = datePresetRange(filters.datePreset, today) || { from: filters.date, to: nextIsoDate(filters.date) };
  return {
    startsAtFrom: seoulDayStart(range.from),
    startsAtTo: seoulDayStart(range.to),
    minRemainingSeats: filters.minRemainingSeats,
    // 파라미터 이름은 계약대로 복수형이며 화면에서는 한 값만 고른다.
    experienceLevels: filters.experienceLevel,
    rulemasterOnly: filters.rulemasterOnly
  };
}

// 요청에 실제로 담기는 값만 비교해 조회 의존성과 초기화 버튼 노출을 판정한다.
function roomFilterKey(filters, today) {
  return JSON.stringify(roomFilterParameters(filters, today));
}

const EMPTY_ROOM_FILTER_KEY = roomFilterKey(EMPTY_ROOM_FILTERS, '');

function roomFilterChips(filters, onChange, roomType, onRoomTypeChange) {
  const update = (patch) => onChange({ ...filters, ...patch });
  const chips = [];
  const type = ROOM_TYPE_FILTERS.find((filter) => filter.value === roomType);
  if (roomType) chips.push({ key: 'type', label: type.label, onClear: () => onRoomTypeChange('') });
  if (filters.datePreset) chips.push({ key: 'datePreset', label: DATE_PRESET_LABEL[filters.datePreset], onClear: () => update({ datePreset: '' }) });
  if (filters.date) chips.push({ key: 'date', label: formatRoomDate(filters.date), onClear: () => update({ date: '' }) });
  if (filters.minRemainingSeats) chips.push({ key: 'seats', label: filters.minRemainingSeats + '자리 이상', onClear: () => update({ minRemainingSeats: '' }) });
  if (filters.experienceLevel) chips.push({ key: 'experience', label: EXP_LABEL[filters.experienceLevel], onClear: () => update({ experienceLevel: '' }) });
  if (filters.rulemasterOnly) chips.push({ key: 'rulemaster', label: '룰마스터 진행', onClear: () => update({ rulemasterOnly: false }) });
  return chips;
}

function RoomFilters({ filters, onChange, today, roomType, onRoomTypeChange, counts, searchSlot }) {
  const update = (patch) => onChange({ ...filters, ...patch });
  const withCount = (filter) => filter.label + (counts ? ' (' + counts[filter.value] + ')' : '');
  return (
    <FilterPanel
      chips={roomFilterChips(filters, onChange, roomType, onRoomTypeChange)}
      onReset={() => { onRoomTypeChange(''); onChange(EMPTY_ROOM_FILTERS); }}
      searchSlot={searchSlot}
    >
      <FilterRadioGroup name="room-filter-type" label="유형" value={roomType} onChange={onRoomTypeChange}
        options={ROOM_TYPE_FILTERS.map((filter) => ({ value: filter.value, label: withCount(filter) }))} />
      <FilterRadioGroup name="room-filter-date" label="날짜" value={filters.date ? DATE_EXACT : filters.datePreset}
        onChange={(value) => update(value === DATE_EXACT ? { datePreset: '', date: defaultRoomDate(today) } : { datePreset: value, date: '' })}
        options={[
          { value: '', label: '전체' },
          ...Object.entries(DATE_PRESET_LABEL).map(([code, label]) => ({ value: code, label })),
          { value: DATE_EXACT, label: '날짜 지정' }
        ]}>
        {!!filters.date && (
          <div className="filter-option-picker">
            <DatePicker id="room-filter-date-exact" value={filters.date} onChange={(date) => update({ date, datePreset: '' })} today={today} placeholder="날짜 지정" />
          </div>
        )}
      </FilterRadioGroup>
      <FilterRadioGroup name="room-filter-seats" label="최소 남은 자리" value={filters.minRemainingSeats} onChange={(minRemainingSeats) => update({ minRemainingSeats })}
        options={[{ value: '', label: '전체' }, ...CAPACITY_OPTIONS.map((seats) => ({ value: String(seats), label: seats + '자리 이상' }))]} />
      <FilterRadioGroup name="room-filter-experience" label="경험 수준" value={filters.experienceLevel} onChange={(experienceLevel) => update({ experienceLevel })}
        options={[{ value: '', label: '전체' }, ...Object.entries(EXP_LABEL).map(([code, label]) => ({ value: code, label }))]} />
      <FilterCheckGroup label="진행" checked={filters.rulemasterOnly} onChange={(rulemasterOnly) => update({ rulemasterOnly })} text="룰마스터 진행만" />
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

function FindRoomsView({ roomType, onRoomTypeChange, roomQuery, onRoomQueryChange, roomFilters, onRoomFiltersChange, dataVersion }) {
  const [input, setInput] = useState(roomQuery);
  const keyword = roomQuery.trim();
  const today = useSeoulToday();
  const filterKey = roomFilterKey(roomFilters, today);
  const counts = useRoomTypeCounts(keyword, roomFilters, today, dataVersion);
  const { data, loading, error, setPage } = usePaginatedRequest(
    // 유형을 비우면 두 유형의 공개 방을 함께 받는다.
    (page, signal) => api.getRooms({ type: roomType, keyword, ...roomFilterParameters(roomFilters, today), page, size: ROOM_LIST_PAGE_SIZE }, signal),
    [roomType, keyword, filterKey, dataVersion]
  );
  // 서버가 페이지네이션 전에 상태를 걸러주지 않아, 프런트에서 상태로 거르면 페이지당 건수·전체 건수가 어긋나고
  // 모집 마감이라도 대기 신청이 가능한 방의 진입점이 사라진다. 서버가 주는 공개 방을 그대로 보여준다.
  const rooms = (data?.content || []).map(normalizeRoom);
  useEffect(() => setInput(roomQuery), [roomQuery]);
  return (
    <>
      <SearchHeader
        icon="rooms"
        title="모임 찾기"
        keywordId="room-q"
        keywordLabel="모임 제목 검색"
        inputValue={input}
        onInputChange={(event) => setInput(event.target.value)}
        onSubmit={(event) => { event.preventDefault(); onRoomQueryChange(input.trim()); }}
        placeholder="모임 제목으로 검색"
        actionSlot={<a className="btn ghost" href="#/create">모임 만들기<SectionIcon name="pencil" /></a>}
        filtersSlot={(searchSlot) => <RoomFilters searchSlot={searchSlot} filters={roomFilters} onChange={onRoomFiltersChange} today={today} roomType={roomType} onRoomTypeChange={onRoomTypeChange} counts={counts} />}
      />
      {error && <ErrorBox message={error} />}
      {!error && loading && !data && <LoadingBox />}
      {!error && !!rooms.length && <div className="grid cols2 list-swappable" style={{ opacity: loading ? 0.6 : 1 }}>{rooms.map((room) => <SessionCard key={room.id} room={room} />)}</div>}
      {!error && !loading && !rooms.length && <div className="infobox">조건에 맞는 공개 모임이 없어요. 직접 모임을 열어보세요.</div>}
      {!error && !!rooms.length && <Pagination page={data?.page ?? 0} totalPages={data?.totalPages ?? 0} loading={loading} onChange={setPage} />}
    </>
  );
}

function SessionActions({ room, me, onApply, onCancelApply, onHostCancel, onFinish }) {
  const [pending, setPending] = useState(false);
  const status = sessionStatus(room);
  const run = (action) => async () => {
    setPending(true);
    try {
      await action(room.id);
    } finally {
      setPending(false);
    }
  };

  if (!me) return <><div className="infobox">참가하거나 모임을 관리하려면 로그인해주세요.</div><a className="btn big" style={{ marginTop: 9 }} href="#/auth">로그인</a></>;
  if (isHost(room)) {
    if (status === 'RECRUITING') {
      return <><div className="page-actions">{canEdit(room) && <a className="btn ghost" href={'#/edit/' + room.id}>모임 수정</a>}<button className="btn redline" disabled={pending} type="button" onClick={run(onHostCancel)}>{pending ? '처리 중…' : '모임 취소'}</button></div>{canEdit(room) && <p className="hint">시작 전이며 다른 활성 참가자가 없을 때만 수정할 수 있어요.</p>}</>;
    }
    if (status === 'CLOSED') {
      return hasStarted(room)
        ? <div className="page-actions"><button className="btn green" disabled={pending} type="button" onClick={run(onFinish)}>{pending ? '처리 중…' : '모임 종료'}</button><button className="btn redline" disabled={pending} type="button" onClick={run(onHostCancel)}>모임 취소</button></div>
        : <button className="btn redline" disabled={pending} type="button" onClick={run(onHostCancel)}>{pending ? '처리 중…' : '모임 취소'}</button>;
    }
    return <div className="infobox gray">{status === 'FINISHED' ? '종료된 모임입니다.' : '취소된 모임입니다.'}</div>;
  }
  if (status === 'CANCELED' || status === 'FINISHED') return <div className="infobox gray">{status === 'CANCELED' ? '취소된 모임입니다.' : '종료된 모임입니다.'}</div>;
  if (isJoined(room)) {
    return (status === 'RECRUITING' || status === 'CLOSED') && !hasStarted(room)
      ? <><div className="infobox green">🎉 참가 중입니다.</div><button className="btn ghost big" disabled={pending} style={{ marginTop: 9 }} type="button" onClick={run(onCancelApply)}>{pending ? '처리 중…' : '참가 취소'}</button></>
      : <div className="infobox green">🎉 참가 중입니다.</div>;
  }
  if (room.joinable) return <button className="btn big" disabled={pending} type="button" onClick={run(onApply)}>{pending ? '처리 중…' : '🙋 참가 신청하기'}</button>;
  return <div className="infobox amber">모집이 마감되었거나 지금은 참가할 수 없어요.</div>;
}

export function SessionDetailView({ sessionId, me, onApply, onCancelApply, onHostCancel, onFinish, dataVersion }) {
  const { data, loading, error } = useRequest(
    async (signal) => normalizeRoom(await api.getRoom(sessionId, signal)),
    [sessionId, dataVersion]
  );
  if (error) return <ErrorBox message={error} />;
  if (loading && !data) return <LoadingBox />;
  const room = data;
  if (!room) return <div className="card">모임을 찾을 수 없어요.</div>;
  const status = statusMeta(room);
  const canEnterChat = Boolean(room.myRole && (status.code === 'RECRUITING' || status.code === 'CLOSED'));
  const privateView = Boolean(room.myRole);
  const game = room.game;
  const banners = {
    RECRUITING: ['green', '✅ 참가 신청을 받는 중입니다'],
    CLOSED: ['amber', '⏳ 모집이 마감되었습니다'],
    CANCELED: ['red', '❌ 주최자가 취소한 모임입니다'],
    FINISHED: ['gray', '🏁 종료된 모임입니다']
  };
  const banner = banners[status.code] || banners.CLOSED;
  return (
    <>
      <div className={'banner ' + banner[0]}>{banner[1]}</div>
      <div className="layout wide">
        <div>
          <div className="card">
            <div className="detail-head">
              <div className="dart">{game ? '🎲' : '🙌'}</div>
              <div style={{ flex: 1 }}>
                <h2>{room.title} <span className={'badge ' + (room.roomType === 'PERSON_FOCUSED' ? 'people' : 'game')}>{room.roomType === 'PERSON_FOCUSED' ? '사람 중심' : '게임 중심'}</span></h2>
                <p className="smeta">{game ? '🎲 ' + game.title : '게임은 모임에서 정해요'}</p>
                {room.description && <p style={{ color: 'var(--brown2)', marginTop: 10 }}>{room.description}</p>}
                {canEnterChat && <div className="page-actions" style={{ marginTop: 15 }}><a className="btn ghost chat-entry" href={'#/chat/' + room.id}>💬 모임 채팅</a></div>}
                <table className="metatable"><tbody>
                  <tr><td>일시</td><td>{formatStartsAt(room.startsAt)}</td></tr>
                  {privateView
                    ? <><tr><td>장소</td><td>{room.region || '홍대'} · {room.place}</td></tr><tr><td>주최자</td><td>{room.host?.nickname}{isHost(room) ? ' (나)' : ''}</td></tr></>
                    : <tr><td>장소</td><td>{room.region || '홍대'} · 참가 확정 후 확인할 수 있어요.</td></tr>}
                  <tr><td>정원</td><td>총 {participantCount(room)}/{room.recruitmentCapacity + 1}명</td></tr>
                  <tr><td>경험 수준</td><td>{EXP_LABEL[room.experienceLevel]}</td></tr>
                  <tr><td>진행</td><td>{room.isRulemasterLed ? '룰마스터 진행 (개설자 자기신고)' : '참가자끼리 진행'}</td></tr>
                </tbody></table>
              </div>
            </div>
          </div>
          {privateView
            ? <section><h2><SectionIcon name="rooms" />참가자 <span className="cnt">총 {participantCount(room)}/{room.recruitmentCapacity + 1}명</span></h2><div className="card"><div className="srow" style={{ marginTop: 0 }}><SeatIcons room={room} /></div><div>{room.participants.map((participant, index) => <span className="pchip" key={participant.nickname + '-' + index}>🙂 {participant.nickname}</span>)}{!room.participants.length && <span className="hint">아직 참가자가 없어요.</span>}</div></div></section>
            : <section><h2><SectionIcon name="rooms" />참가자</h2><div className="infobox">정확한 장소와 참가자 목록은 주최자 또는 현재 참가자만 확인할 수 있어요.</div></section>}
        </div>
        <aside><div className="card"><SessionActions room={room} me={me} onApply={onApply} onCancelApply={onCancelApply} onHostCancel={onHostCancel} onFinish={onFinish} /></div></aside>
      </div>
    </>
  );
}

// 팝오버 바깥을 누르거나 Escape를 누르면 닫는다. Escape는 트리거로 초점을 되돌린다.
function usePopoverDismiss(isOpen, containerRef, onDismiss) {
  useEffect(() => {
    if (!isOpen) return undefined;
    const closeWhenOutside = (event) => {
      if (!containerRef.current?.contains(event.target)) onDismiss(false);
    };
    const closeOnEscape = (event) => {
      if (event.key === 'Escape') onDismiss(true);
    };
    document.addEventListener('pointerdown', closeWhenOutside);
    window.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('pointerdown', closeWhenOutside);
      window.removeEventListener('keydown', closeOnEscape);
    };
  }, [isOpen]);
}

// placeholder를 주면 값을 비운 상태를 허용한다. 필터처럼 날짜를 고르지 않는 선택지가 있을 때 쓴다.
function DatePicker({ id, value, onChange, today, placeholder }) {
  const selectedDate = dateParts(value) ? value : (placeholder ? '' : defaultRoomDate(today));
  const openDate = selectedDate || today;
  const [isOpen, setIsOpen] = useState(false);
  const [draftDate, setDraftDate] = useState(openDate);
  const [visibleMonth, setVisibleMonth] = useState(() => monthFromIsoDate(openDate));
  const pickerRef = useRef(null);
  const triggerRef = useRef(null);

  const openPicker = () => {
    setDraftDate(openDate);
    setVisibleMonth(monthFromIsoDate(openDate));
    setIsOpen(true);
  };
  const closePicker = (restoreFocus = false) => {
    setIsOpen(false);
    if (restoreFocus) window.setTimeout(() => triggerRef.current?.focus(), 0);
  };
  usePopoverDismiss(isOpen, pickerRef, closePicker);
  const moveMonth = (offset) => setVisibleMonth((month) => new Date(month.getFullYear(), month.getMonth() + offset, 1));
  const monthYear = visibleMonth.getFullYear();
  const monthIndex = visibleMonth.getMonth();
  const todayMonth = monthFromIsoDate(today);
  const isFirstSelectableMonth = monthYear === todayMonth.getFullYear() && monthIndex === todayMonth.getMonth();
  const monthStartsOn = new Date(monthYear, monthIndex, 1).getDay();
  const days = Array.from({ length: 42 }, (_, index) => {
    const date = new Date(monthYear, monthIndex, index - monthStartsOn + 1);
    const isoDate = isoDateFromParts(date.getFullYear(), date.getMonth(), date.getDate());
    return { isoDate, day: date.getDate(), isCurrentMonth: date.getMonth() === monthIndex, isPast: isoDate < today, weekday: date.getDay() };
  });

  return (
    <div className="date-picker" ref={pickerRef}>
      <button id={id} ref={triggerRef} type="button" className="date-picker-trigger" aria-label={selectedDate ? '날짜 ' + formatCalendarDate(selectedDate) : placeholder} aria-expanded={isOpen} aria-haspopup="dialog" aria-controls={isOpen ? id + '-calendar' : undefined} onClick={() => isOpen ? closePicker() : openPicker()}>
        <span className={'date-picker-value' + (selectedDate ? '' : ' empty')}>{selectedDate ? formatRoomDate(selectedDate) : placeholder}</span>
      </button>
      {isOpen && (
        <section id={id + '-calendar'} className="date-picker-popover" role="dialog" aria-label="날짜 선택">
          <div className="date-picker-header">
            <div className="date-picker-month"><strong>{monthYear}년 {monthIndex + 1}월</strong></div>
            <div className="date-picker-navigation"><button type="button" aria-label="이전 달" disabled={isFirstSelectableMonth} onClick={() => moveMonth(-1)}>‹</button><button type="button" aria-label="다음 달" onClick={() => moveMonth(1)}>›</button><button type="button" className="date-picker-close" aria-label="날짜 선택 닫기" onClick={() => closePicker(true)}>×</button></div>
          </div>
          <div className="date-picker-weekdays" aria-hidden="true">{WEEKDAY_LABELS.map((weekday, index) => <span className={index === 0 ? 'sun' : index === 6 ? 'sat' : ''} key={weekday}>{weekday}</span>)}</div>
          <div className="date-picker-days">{days.map((day) => <button type="button" key={day.isoDate} className={['date-picker-day', !day.isCurrentMonth && 'outside', day.isoDate === today && 'today', day.isoDate === draftDate && 'selected', day.weekday === 0 && 'sun', day.weekday === 6 && 'sat'].filter(Boolean).join(' ')} aria-label={formatCalendarDate(day.isoDate)} aria-pressed={day.isoDate === draftDate} disabled={day.isPast} onClick={() => { setDraftDate(day.isoDate); if (!day.isCurrentMonth) setVisibleMonth(monthFromIsoDate(day.isoDate)); }}>{day.day}</button>)}</div>
          <div className="date-picker-footer"><button type="button" className="date-picker-today" onClick={() => { setDraftDate(today); setVisibleMonth(monthFromIsoDate(today)); }}>오늘</button>{placeholder && <button type="button" className="date-picker-today" onClick={() => { onChange(''); closePicker(true); }}>선택 해제</button>}<button type="button" className="date-picker-confirm" onClick={() => { onChange(draftDate); closePicker(true); }}>선택 완료</button></div>
        </section>
      )}
    </div>
  );
}

function TimePicker({ id, value, onChange }) {
  const [isOpen, setIsOpen] = useState(false);
  const [draft, setDraft] = useState(() => timeParts(value));
  const pickerRef = useRef(null);
  const triggerRef = useRef(null);
  const selectedHourRef = useRef(null);

  const openPicker = () => {
    setDraft(timeParts(value));
    setIsOpen(true);
  };
  const closePicker = (restoreFocus = false) => {
    setIsOpen(false);
    if (restoreFocus) window.setTimeout(() => triggerRef.current?.focus(), 0);
  };
  usePopoverDismiss(isOpen, pickerRef, closePicker);

  // 시 목록은 12개라 스크롤되므로 열 때 선택한 시를 컬럼 가운데로 옮긴다.
  // scrollIntoView는 팝오버 바깥의 페이지까지 함께 스크롤하므로 컬럼만 직접 움직인다.
  useEffect(() => {
    const option = selectedHourRef.current;
    if (!isOpen || !option) return;
    const column = option.parentElement;
    column.scrollTop = option.offsetTop - (column.clientHeight - option.clientHeight) / 2;
  }, [isOpen]);

  // 네이티브 입력으로 저장한 10분 단위 밖의 시간도 선택 상태로 보이게 한다.
  const minutes = MINUTE_OPTIONS.includes(draft.minute) ? MINUTE_OPTIONS : [...MINUTE_OPTIONS, draft.minute].sort((left, right) => left - right);

  return (
    <div className="date-picker time-picker" ref={pickerRef}>
      <button id={id} ref={triggerRef} type="button" className="date-picker-trigger" aria-label={'시간 ' + formatRoomTime(value)} aria-expanded={isOpen} aria-haspopup="dialog" aria-controls={isOpen ? id + '-options' : undefined} onClick={() => isOpen ? closePicker() : openPicker()}>
        <span className="date-picker-value">{formatRoomTime(value)}</span>
      </button>
      {isOpen && (
        <section id={id + '-options'} className="date-picker-popover" role="dialog" aria-label="시간 선택">
          <div className="date-picker-header">
            <div className="date-picker-month"><strong>{formatRoomTime(timeFromParts(draft))}</strong></div>
            <div className="date-picker-navigation"><button type="button" className="date-picker-close" aria-label="시간 선택 닫기" onClick={() => closePicker(true)}>×</button></div>
          </div>
          <div className="time-picker-columns">
            <div className="time-picker-column" role="group" aria-label="오전 오후">
              {[false, true].map((isAfternoon) => (
                <button type="button" key={String(isAfternoon)} className={'time-picker-option' + (draft.isAfternoon === isAfternoon ? ' selected' : '')} aria-pressed={draft.isAfternoon === isAfternoon} onClick={() => setDraft({ ...draft, isAfternoon })}>{isAfternoon ? '오후' : '오전'}</button>
              ))}
            </div>
            <div className="time-picker-column" role="group" aria-label="시">
              {HOUR_OPTIONS.map((hour) => (
                <button type="button" key={hour} ref={draft.hour === hour ? selectedHourRef : null} className={'time-picker-option' + (draft.hour === hour ? ' selected' : '')} aria-pressed={draft.hour === hour} onClick={() => setDraft({ ...draft, hour })}>{hour}</button>
              ))}
            </div>
            <div className="time-picker-column" role="group" aria-label="분">
              {minutes.map((minute) => (
                <button type="button" key={minute} className={'time-picker-option' + (draft.minute === minute ? ' selected' : '')} aria-pressed={draft.minute === minute} onClick={() => setDraft({ ...draft, minute })}>{zeroPad(minute)}</button>
              ))}
            </div>
          </div>
          <div className="date-picker-footer"><button type="button" className="date-picker-confirm" onClick={() => { onChange(timeFromParts(draft)); closePicker(true); }}>선택 완료</button></div>
        </section>
      )}
    </div>
  );
}

function RoomFormFields({ form, onChange, roomType, onOpenGamePicker, today }) {
  const gameFocused = roomType === 'GAME_FOCUSED';
  const update = (field, value) => onChange({ ...form, [field]: value });
  const selectedGame = form.selectedGame;
  return (
    <>
      <div className="formrow">
        <div><div className="field-label-row"><label>게임 {gameFocused ? '(필수)' : '(선택)'}</label><button type="button" className="game-search-open" onClick={onOpenGamePicker}>게임 검색</button></div><div className="game-selected-value">{selectedGame ? selectedGame.title : '선택한 게임이 없어요'}</div><p className="hint">{gameFocused ? '게임 검색으로 선택해주세요.' : '게임 없이 모임을 만들 수도 있어요.'}</p></div>
        <div><label htmlFor="room-title">모임 제목</label><input id="room-title" maxLength="100" value={form.title} onChange={(event) => update('title', event.target.value)} placeholder="예: 토요일 오후 같이 게임 고를 분" /></div>
      </div>
      <div className="formrow single"><div><label htmlFor="room-description">설명 (선택, 255자 이내)</label><textarea id="room-description" maxLength="255" value={form.description} onChange={(event) => update('description', event.target.value)} placeholder="예: 처음 오신 분도 환영합니다." /></div></div>
      <div className="formrow"><div><label htmlFor="room-date">날짜</label><DatePicker id="room-date" value={form.date} onChange={(date) => update('date', date)} today={today} /></div><div><label htmlFor="room-time">시간</label><TimePicker id="room-time" value={form.time} onChange={(time) => update('time', time)} /></div></div>
      <div className="formrow"><div><label>지역</label><div className="game-selected-value">홍대</div></div><div><label htmlFor="room-place">장소</label><input id="room-place" maxLength="100" value={form.place} onChange={(event) => update('place', event.target.value)} placeholder="예: 홍대입구역 인근 OO보드게임카페" /></div></div>
      <div className="formrow"><div><label htmlFor="room-capacity">모집 정원 (본인 제외, 1~10명)</label><select id="room-capacity" value={form.recruitmentCapacity} onChange={(event) => update('recruitmentCapacity', Number(event.target.value))}>{CAPACITY_OPTIONS.map((capacity) => <option value={capacity} key={capacity}>{capacity}명</option>)}</select></div><div><label htmlFor="room-experience">경험 수준</label><select id="room-experience" value={form.experienceLevel} onChange={(event) => update('experienceLevel', event.target.value)}>{Object.entries(EXP_LABEL).map(([code, label]) => <option value={code} key={code}>{label}</option>)}</select></div></div>
      <label className="checkline"><input type="checkbox" checked={form.isRulemasterLed} onChange={(event) => update('isRulemasterLed', event.target.checked)} /> 룰마스터 진행 (개설자 자기신고)</label>
    </>
  );
}

function CreateView({ createMode, onCreateModeChange, initialGame, onCreate, today }) {
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
    <>
      <h2><SectionIcon name="pencil" />모임 만들기</h2>
      <div className="layout wide">
        <form className="card" onSubmit={submit}>
          <label>모임 유형</label>
          <div className="formrow">
            <button type="button" className={'modecard ' + (gameFocused ? 'on' : '')} onClick={() => onCreateModeChange('GAME_FOCUSED')}><b>🎲 게임 중심</b><span>게임을 먼저 정하고 사람을 모아요. 게임 선택은 필수예요.</span></button>
            <button type="button" className={'modecard ' + (!gameFocused ? 'on' : '')} onClick={() => onCreateModeChange('PERSON_FOCUSED')}><b>🙌 사람 중심</b><span>함께할 사람부터 모아요. 게임 선택은 선택이에요.</span></button>
          </div>
          <RoomFormFields form={form} onChange={setForm} roomType={createMode} onOpenGamePicker={() => setGamePickerOpen(true)} today={today} />
          <button className="btn big create-submit" style={{ marginTop: 14 }} disabled={submitting} type="submit">{submitting ? '모임을 여는 중…' : '모임 열기'}</button>
        </form>
        <aside>
          <div className="card infobox create-note">
            <b>📌 개설 안내</b>
            <ul>
              <li>게임 중심은 게임 선택이 필수예요.</li>
              <li>사람 중심은 게임 없이 열 수 있어요.</li>
              <li>지역은 홍대로 고정되고, 장소만 입력해요.</li>
              <li>모집 정원은 주최자 제외 1명 이상 10명 이하예요.</li>
            </ul>
          </div>
        </aside>
      </div>
      <GamePickerDialog isOpen={gamePickerOpen} selectedGameId={form.gameId} allowClear={!gameFocused} onSelect={(game) => setForm((current) => ({ ...current, gameId: game.id, selectedGame: game }))} onClear={() => setForm((current) => ({ ...current, gameId: '', selectedGame: null }))} onClose={() => setGamePickerOpen(false)} />
    </>
  );
}

function EditSessionForm({ room, onSave, today }) {
  const [form, setForm] = useState(() => roomFormFromRoom(room));
  const [gamePickerOpen, setGamePickerOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  if (!canEdit(room)) return <div className="card">지금은 이 모임을 수정할 수 없어요.</div>;
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
    <>
      <h2><SectionIcon name="pencil" />모임 수정</h2>
      <form className="card" style={{ maxWidth: 780 }} onSubmit={submit}>
        <div className="infobox" style={{ marginBottom: 16 }}>{room.roomType === 'GAME_FOCUSED' ? '게임 중심' : '사람 중심'} 모임 · 유형과 지역은 수정할 수 없어요.</div>
        <RoomFormFields form={form} onChange={setForm} roomType={room.roomType} onOpenGamePicker={() => setGamePickerOpen(true)} today={today} />
        <div className="page-actions" style={{ marginTop: 16 }}><button className="btn" disabled={submitting} type="submit">{submitting ? '저장 중…' : '수정 저장'}</button><a className="btn ghost" href={'#/session/' + room.id}>취소</a></div>
      </form>
      <GamePickerDialog isOpen={gamePickerOpen} selectedGameId={form.gameId} allowClear={room.roomType === 'PERSON_FOCUSED'} onSelect={(game) => setForm((current) => ({ ...current, gameId: game.id, selectedGame: game }))} onClear={() => setForm((current) => ({ ...current, gameId: '', selectedGame: null }))} onClose={() => setGamePickerOpen(false)} />
    </>
  );
}

/** 참가 중이며 채팅에 들어갈 수 있는 모임만 골라 채팅방 목록으로 보여준다. */
export function ChatListView({ dataVersion }) {
  const [keyword, setKeyword] = useState('');
  const joined = useRequest((signal) => api.getMyRooms({ role: 'joined', page: 0, size: 100 }, signal), [dataVersion]);
  const hosted = useRequest((signal) => api.getMyRooms({ role: 'hosted', page: 0, size: 100 }, signal), [dataVersion]);
  const loading = joined.loading || hosted.loading;
  const error = joined.error || hosted.error;
  const rooms = [...(joined.data?.content || []), ...(hosted.data?.content || [])].map(normalizeRoom);
  const chatRooms = rooms.filter((room) => {
    const status = statusMeta(room);
    return Boolean(room.myRole) && (status.code === 'RECRUITING' || status.code === 'CLOSED');
  });
  const seen = new Set();
  const list = chatRooms
    .filter((room) => (seen.has(room.id) ? false : (seen.add(room.id), true)))
    .filter((room) => room.title.toLowerCase().includes(keyword.trim().toLowerCase()))
    .sort((a, b) => new Date(a.startsAt) - new Date(b.startsAt));
  return (
    <>
      <h2><SectionIcon name="chat" />채팅</h2>
      <div className="inline-search">
        <label className="sr-only" htmlFor="chat-list-q">모임 제목으로 채팅방 찾기</label>
        <input id="chat-list-q" value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="모임 제목으로 찾기" />
        <span aria-hidden="true"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="10.5" cy="10.5" r="6.5" /><line x1="21.5" y1="21.5" x2="15.3" y2="15.3" /></svg></span>
      </div>
      {error && <ErrorBox message={error} />}
      {!error && loading && !list.length && <LoadingBox />}
      {!error && !loading && !chatRooms.length && <div className="infobox">지금 채팅할 수 있는 모임이 없어요.</div>}
      {!error && !!chatRooms.length && !list.length && <div className="infobox">'{keyword}'와 일치하는 채팅방이 없어요.</div>}
      {!error && !!list.length && (
        <div className="card menu-list" style={{ maxWidth: 560 }}>
          {list.map((room) => (
            <a className="menu-row" href={'#/chat/' + room.id} key={room.id}>
              <span className="menu-icon" aria-hidden="true"><SectionIcon name="chat" /></span>
              <span className="menu-label">{room.title}</span>
              <span className="hint" style={{ marginRight: 4 }}>{formatStartsAt(room.startsAt)}</span>
              <span className="menu-arrow" aria-hidden="true">›</span>
            </a>
          ))}
        </div>
      )}
    </>
  );
}

function EditView({ sessionId, onSave, dataVersion, today }) {
  const { data, loading, error } = useRequest(
    async (signal) => normalizeRoom(await api.getRoom(sessionId, signal)),
    [sessionId, dataVersion]
  );
  if (error) return <ErrorBox message={error} />;
  if (loading && !data) return <LoadingBox />;
  if (!data) return <div className="card">지금은 이 모임을 수정할 수 없어요.</div>;
  return <EditSessionForm key={data.id} room={data} onSave={onSave} today={today} />;
}

export function MyRoomsSection({ myTab, onMyTabChange, dataVersion, onCancelApply }) {
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
  const joinedCount = joined.data?.totalElements ?? '—';
  const hostedCount = hosted.data?.totalElements ?? '—';
  return (
    <>
      <h2><SectionIcon name="list" />내 모임</h2>
      <div className="tabs-row">
        <div className="tabs">
          <button type="button" className={tab === 'joined' ? 'on' : ''} onClick={() => onMyTabChange('joined')}><span className="tab-full">참가한 모임 ({joinedCount})</span><span className="tab-short">참가 {joinedCount}</span></button>
          <button type="button" className={tab === 'hosted' ? 'on' : ''} onClick={() => onMyTabChange('hosted')}><span className="tab-full">개설한 모임 ({hostedCount})</span><span className="tab-short">개설 {hostedCount}</span></button>
        </div>
        <a className="btn ghost" href="#/create">모임 만들기<SectionIcon name="pencil" /></a>
      </div>
      {page.error && <ErrorBox message={page.error} />}
      {!page.error && page.loading && !page.data && <LoadingBox />}
      {!page.error && !!list.length && (
        <div className="session-list">
          {list.map((room) => (tab === 'joined'
            ? <MyRoomListItem key={room.id} room={room} onCancelApply={onCancelApply} />
            : <SessionCard key={room.id} room={room} />))}
        </div>
      )}
      {!page.error && !page.loading && !list.length && (
        <div className="infobox">
          {tab === 'joined'
            ? <>아직 참가한 모임이 없어요. <a className="infobox-action" href="#/find">모임 찾아보기 →</a></>
            : '아직 개설한 모임이 없어요. 위 버튼으로 첫 모임을 열어보세요.'}
        </div>
      )}
      {!page.error && !!list.length && <Pagination page={page.data?.page ?? 0} totalPages={page.data?.totalPages ?? 0} loading={page.loading} onChange={page.setPage} />}
      {!page.error && !!list.length && <p className="hint" style={{ marginTop: 14 }}>카드는 공개 모임 정보만 표시하고, 정확한 장소와 참가자 목록은 모임 상세에서 권한에 따라 확인할 수 있어요.</p>}
    </>
  );
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
function groupChatMessages(messages) {
  const sameGroup = (left, right) => Boolean(left) && Boolean(right)
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

// 직접 URL로 들어와도 진입 여부는 서버 응답으로만 정해진다. 거절은 서버 원문 대신 계약된 code로 안내한다.
function chatAccessError(error) {
  const message = error instanceof ApiError ? CHAT_ACCESS_MESSAGE[error.code] : undefined;
  return message ? new ApiError({ status: error.status, code: error.code, message }) : error;
}

const CHAT_RECONNECT_LIMIT = 5;
const CHAT_RECONNECT_DELAYS = [500, 1000, 2000, 4000, 8000];

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

export function ChatRoomView({ roomId, dataVersion, me }) {
  const { data, loading, error } = useRequest(
    (signal) => api.getChatMessages(roomId, signal).catch((cause) => { throw chatAccessError(cause); }),
    [roomId, dataVersion]
  );
  // 옆 열에 띄울 모임 정보. 못 불러와도 대화는 그대로 보여준다.
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
  const [streamStatus, setStreamStatus] = useState('connecting');
  const [streamError, setStreamError] = useState('');
  const lastEventIdRef = useRef(null);
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
    setClientMessageId(createClientMessageId());
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

  useEffect(() => {
    if (!data || error) return undefined;
    let active = true;
    let socket;
    let reconnectTimer;
    let stableConnectionTimer;
    let reconnectAttempts = 0;

    const connect = () => {
      if (!active) return;
      setStreamStatus(reconnectAttempts === 0 ? 'connecting' : 'reconnecting');
      try {
        socket = api.openChatWebSocket(roomId, { afterMessageId: lastEventIdRef.current });
      } catch (cause) {
        if (!active) return;
        setStreamStatus('closed');
        setStreamError(messageForError(cause, '실시간 채팅을 연결하지 못했어요.'));
        return;
      }
      socket.onopen = () => {
        if (!active) return;
        setStreamStatus('connected');
        setStreamError('');
        stableConnectionTimer = setTimeout(() => { reconnectAttempts = 0; }, 10000);
      };
      socket.onmessage = (event) => {
        if (!active) return;
        try {
          const payload = JSON.parse(event.data);
          const message = chatStreamMessage(payload, roomId);
          if (!message) return;
          const eventId = Number(payload.eventId);
          lastEventIdRef.current = Math.max(lastEventIdRef.current || 0, eventId);
          mergeMessages([message]);
        } catch {
          setStreamError('실시간 메시지 형식을 확인하지 못했어요.');
        }
      };
      socket.onerror = () => {
        if (active) setStreamError('실시간 연결이 불안정해요. 다시 연결하는 중…');
      };
      socket.onclose = (event) => {
        if (!active) return;
        clearTimeout(stableConnectionTimer);
        if (event?.code === 1008) {
          setStreamStatus('closed');
          setStreamError('채팅 접근 권한이 종료되어 실시간 연결을 닫았어요.');
          return;
        }
        if (reconnectAttempts >= CHAT_RECONNECT_LIMIT) {
          setStreamStatus('closed');
          setStreamError('실시간 연결을 복구하지 못했어요. 새로고침 후 다시 시도해주세요.');
          return;
        }
        setStreamStatus('reconnecting');
        setStreamError('실시간 연결이 끊겨 다시 연결하는 중…');
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
    // 그러면 실시간 병합이 먼저 목록 길이를 바꿔도 그 시점에 소비되어 하단 이동이 지연되지 않는다.
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
  // 실패해도 되돌려 사용자가 고쳐서 다시 보낼 수 있게 한다. 최초 렌더에서는 플래그가 없어 포커스를 가져가지 않는다.
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

  return (
    <>
      <div className="chat-view">
        <aside className="chat-side">
          <p className="chat-side-kicker"><SectionIcon name="chat" />모임 채팅</p>
          <h2>{roomInfo.data?.title || '모임'}</h2>
          <dl className="chat-facts">
            <div><dt>일시</dt><dd>{roomInfo.data ? formatStartsAt(roomInfo.data.startsAt) : '—'}</dd></div>
            <div><dt>장소</dt><dd>{roomInfo.data?.region || '—'}</dd></div>
            <div><dt>인원</dt><dd>{roomInfo.data?.participantCount ? participantCount(roomInfo.data) + '명' : '—'}</dd></div>
            <div><dt>경험</dt><dd>{EXP_LABEL[roomInfo.data?.experienceLevel] || '—'}</dd></div>
          </dl>
          <div className="chat-side-links">
            <a href={'#/session/' + roomId}>모임 상세로</a>
            <a href="#/my">내 모임</a>
          </div>
        </aside>

        <section className="chat-main">
          {!error && data && streamStatus !== 'connected' && (
            <p className="hint warn" role="status">{streamError || (streamStatus === 'connecting' ? '실시간 채팅에 연결하는 중…' : '실시간 연결을 복구하는 중…')}</p>
          )}
          {!error && data && streamStatus === 'connected' && <p className="hint" role="status">실시간 연결됨</p>}
          <div className="chat-log" ref={chatHistoryRef} onScroll={handleChatScroll}>
            {!error && !!displayedMessages.length && <div className="chat-load-sentinel" ref={loadMoreSentinelRef} aria-hidden="true" />}
            {error && <ErrorBox message={error} />}
            {!error && loading && !data && <LoadingBox label="채팅을 불러오는 중…" />}
            {!error && loadingMore && <p className="hint chat-loading-more" role="status">이전 메시지를 불러오는 중…</p>}
            {!error && !loading && !displayedMessages.length && <p className="chat-empty">아직 주고받은 메시지가 없어요.<br /><span className="hint">모임 전에 인사를 남겨보세요.</span></p>}
            {groupChatMessages(displayedMessages).map((day) => (
              <ul className="chat-day" key={day.day}>
                <li className="chat-daymark" role="presentation"><span>{formatChatDay(day.day)}</span></li>
                {day.rows.map(({ message, isGroupStart, isGroupEnd }) => {
                  const isMine = Boolean(message.isMine);
                  const owner = isMine ? 'mine' : 'theirs';
                  return (
                    <li
                      className={'chat-message ' + owner + (isGroupStart ? ' start' : '')}
                      data-message-owner={owner}
                      key={message.messageId}
                    >
                      {/* 내 이름은 화면에서는 군더더기라 숨기고, 화면 낭독기에는 남긴다. */}
                      {isGroupStart && <b className={'chat-sender' + (isMine ? ' sr-only' : '')}>{isMine ? '나' : message.sender?.nickname}</b>}
                      <span className="chat-line">
                        <span className="chat-content">{message.content}</span>
                        {isGroupEnd && <time className="chat-time" dateTime={message.createdAt}>{formatChatTime(message.createdAt)}</time>}
                      </span>
                    </li>
                  );
                })}
              </ul>
            ))}
          </div>

          {!error && <form className="chat-compose" onSubmit={submit}>
            <label className="sr-only" htmlFor="chat-message">메시지</label>
            <textarea id="chat-message" ref={composeInputRef} disabled={sending} maxLength="500" value={content} onChange={(event) => { setContent(event.target.value); setSendError(''); setSendResultUnknown(false); }} onKeyDown={handleComposeKeyDown} placeholder="메시지를 입력해주세요." />
            <button className="chat-send-btn" disabled={sending} type="submit" aria-label={sending ? '전송 중…' : sendResultUnknown ? '다시 시도' : '전송'}>
              <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M4 12 20 4l-8 16-2-6z" /></svg>
            </button>
            <span className={'chat-count' + ([...content].length > 450 ? ' near' : '')}>{[...content].length}/500</span>
            {sendError && <p className="hint warn chat-senderror" role="alert">{sendError}</p>}
          </form>}
        </section>
      </div>
    </>
  );
}

export function ProfileView({ me, onSave, onLogout, socialProviders = [], onSocialLink, onUploadImage, onDeleteImage }) {
  const [nickname, setNickname] = useState(me.nickname);
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);
  const [linking, setLinking] = useState('');
  const [uploadingImage, setUploadingImage] = useState(false);
  const fileInputRef = useRef(null);
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
  // 연결은 제공자 화면으로 전체 페이지를 넘긴다. 성공하면 이 화면이 다시 그려지지 않으므로 상태를 되돌리지 않는다.
  const startLink = async (provider) => {
    setLinking(provider);
    if (!await onSocialLink(provider)) setLinking('');
  };
  return (
    <>
      <div className="profile-head">
        <div className="profile-avatar-wrap">
          {me.profileImageUrl
            ? <img className="profile-avatar" src={me.profileImageUrl} alt={me.nickname + ' 프로필 이미지'} />
            : <span className="profile-avatar" aria-hidden="true">{me.nickname.slice(0, 1)}</span>}
          <button className="profile-avatar-edit" type="button" disabled={uploadingImage} onClick={() => fileInputRef.current?.click()} aria-label="프로필 이미지 변경">
            {uploadingImage ? '…' : <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 20h9" /><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z" /></svg>}
          </button>
          <input ref={fileInputRef} type="file" accept="image/jpeg,image/png,image/webp" style={{ display: 'none' }} onChange={handleImageSelect} />
        </div>
        <div>
          <h2>{me.nickname}</h2>
          {me.profileImageUrl && <button className="btn ghost sm" type="button" disabled={uploadingImage} onClick={handleDeleteImage}>이미지 삭제</button>}
        </div>
      </div>
      <div className="card menu-list" style={{ maxWidth: 560 }}>
          <a className="menu-row" href="#/my">
            <span className="menu-icon" aria-hidden="true"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M8 6h13" /><path d="M8 12h13" /><path d="M8 18h13" /><path d="M3.5 6h.01" /><path d="M3.5 12h.01" /><path d="M3.5 18h.01" /></svg></span>
            <span className="menu-label">내 모임</span>
            <span className="menu-arrow" aria-hidden="true">›</span>
          </a>
          <a className="menu-row" href="#/game-list/played">
            <span className="menu-icon" aria-hidden="true"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="m9 12 2 2 4-4" /><circle cx="12" cy="12" r="9" /></svg></span>
            <span className="menu-label">해 본 게임</span>
            <span className="menu-arrow" aria-hidden="true">›</span>
          </a>
          <div>
            <button className="menu-row" type="button" aria-expanded={editing} onClick={() => { setNickname(me.nickname); setEditing(!editing); }}>
              <span className="menu-icon" aria-hidden="true"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="8" r="4" /><path d="M4 21c0-4.4 3.6-7 8-7s8 2.6 8 7" /></svg></span>
              <span className="menu-label">내 정보</span>
              <span className="menu-arrow" aria-hidden="true">{editing ? '▾' : '›'}</span>
            </button>
            {editing && (
              <form className="menu-panel" onSubmit={submit}>
                <label htmlFor="profile-nickname">닉네임</label>
                <div className="page-actions">
                  <input id="profile-nickname" maxLength="50" autoFocus value={nickname} onChange={(event) => setNickname(event.target.value)} />
                  <button className="btn" disabled={saving} type="submit">{saving ? '저장 중…' : '저장'}</button>
                  <button className="btn ghost" disabled={saving} type="button" onClick={() => setEditing(false)}>취소</button>
                </div>
                <p className="hint">알밤메이트에서 표시되는 내 닉네임입니다.</p>
              </form>
            )}
          </div>
          {socialProviders.length > 0 && (
            <div>
              <div className="menu-row static">
                <span className="menu-icon" aria-hidden="true"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.5 1.5" /><path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.5-1.5" /></svg></span>
                <span className="menu-label">소셜 계정 연결</span>
              </div>
              <div className="menu-panel social-link-list">
                {socialProviders.map((item) => (
                  <div className="social-link-row" key={item.provider}>
                    <span>{SOCIAL_PROVIDER_LABEL[item.provider]}</span>
                    {item.linked
                      ? <span className="social-link-state">연결됨</span>
                      : <button className="btn ghost pill" type="button" disabled={Boolean(linking)} onClick={() => startLink(item.provider)}>{linking === item.provider ? '이동 중…' : SOCIAL_PROVIDER_LABEL[item.provider] + ' 연결'}</button>}
                  </div>
                ))}
                <p className="hint">연결한 계정으로도 로그인할 수 있어요. 연결 해제와 교체는 아직 제공하지 않아요.</p>
              </div>
            </div>
          )}
          <button className="menu-row" type="button" disabled={loggingOut} onClick={logout}>
            <span className="menu-icon" aria-hidden="true"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" /><path d="m16 17 5-5-5-5" /><path d="M21 12H9" /></svg></span>
            <span className="menu-label">{loggingOut ? '로그아웃 중…' : '로그아웃'}</span>
          </button>
      </div>
    </>
  );
}

// 브라우저 minLength와 서버의 Unicode 문자 수 계산이 다를 수 있어 하한도 같은 기준으로 판정한다.
// 안내 문구의 한글 24자는 72바이트를 한글 한 글자 3바이트로 나눈 값이다.
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

export function AuthView({ onLogin, socialProviders = [], onSocialLogin }) {
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
    <div className="auth-modal-backdrop">
      <section className="auth-modal" aria-label="로그인">
        <a className="auth-modal-close" href="#/home" aria-label="닫기">×</a>
        <form onSubmit={submit}>
          <div className="auth-email-header">
            <span className="auth-email-brand"><img src={brandSymbol} alt="" /></span>
            <span className="auth-email-title">알밤메이트로 로그인하기</span>
          </div>
          <div className="formrow single"><div><label className="sr-only" htmlFor="auth-email">이메일</label><input id="auth-email" type="email" autoComplete="email" placeholder="이메일" required value={email} onChange={(event) => setEmail(event.target.value)} /></div><div><label className="sr-only" htmlFor="auth-password">비밀번호</label><div className="auth-password-field"><input id="auth-password" type={showPassword ? 'text' : 'password'} autoComplete="current-password" placeholder="비밀번호" required value={password} onChange={(event) => setPassword(event.target.value)} /><button type="button" className="auth-password-toggle" onClick={() => setShowPassword((visible) => !visible)} aria-label={showPassword ? '비밀번호 숨기기' : '비밀번호 보기'}>{showPassword ? <EyeOffIcon /> : <EyeIcon />}</button></div></div></div>
          {error && <ErrorBox message={error} />}
          <button className="btn big pill" disabled={submitting} type="submit">{submitting ? '처리 중…' : '로그인'}</button>
        </form>
        {socialProviders.length > 0 && (
          <>
            <div className="auth-divider">또는</div>
            <div className="social-auth">
              {socialProviders.map((item) => (
                <button className={'social-auth-btn ' + item.provider.toLowerCase()} key={item.provider} type="button" onClick={() => onSocialLogin(item.provider)} aria-label={SOCIAL_PROVIDER_LABEL[item.provider] + '로 계속하기'}>
                  <span className="social-auth-icon">{SOCIAL_PROVIDER_ICON[item.provider]}</span>
                </button>
              ))}
            </div>
          </>
        )}
        <a className="auth-switch-link" href="#/signup">알밤메이트가 처음이신가요? <u>가입하기</u></a>
      </section>
    </div>
  );
}

export function SignupView({ onSignup }) {
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
    <section className="card signup-page" style={{ margin: '0 auto', maxWidth: 460 }}>
      <h2>회원가입</h2>
      <form onSubmit={submit}>
        <div className="auth-email-header">
          <span className="auth-email-brand"><img src={brandSymbol} alt="" /></span>
          <span className="auth-email-title">알밤메이트로 회원가입하기</span>
        </div>
        <div className="formrow single"><div><label className="sr-only" htmlFor="signup-email">이메일</label><input id="signup-email" type="email" autoComplete="email" placeholder="이메일" required value={email} onChange={(event) => setEmail(event.target.value)} /></div><div><label className="sr-only" htmlFor="signup-nickname">닉네임</label><input id="signup-nickname" maxLength="50" placeholder="닉네임" required value={nickname} onChange={(event) => setNickname(event.target.value)} /></div><div><label className="sr-only" htmlFor="signup-password">비밀번호</label><div className="auth-password-field"><input id="signup-password" ref={passwordRef} type={showPassword ? 'text' : 'password'} autoComplete="new-password" minLength={PASSWORD_MIN_CODE_POINTS} placeholder="비밀번호" required value={password} onChange={(event) => setPassword(event.target.value)} aria-describedby="signup-password-hint" aria-invalid={passwordError ? true : undefined} /><button type="button" className="auth-password-toggle" onClick={() => setShowPassword((visible) => !visible)} aria-label={showPassword ? '비밀번호 숨기기' : '비밀번호 보기'}>{showPassword ? <EyeOffIcon /> : <EyeIcon />}</button></div><p id="signup-password-hint" className={passwordError ? 'hint warn' : 'hint'} role={passwordError ? 'alert' : undefined}>{passwordError || '15자 이상, Unicode와 공백을 사용할 수 있어요.'}</p></div></div>
        {error && <ErrorBox message={error} />}
        <button className="btn big pill" disabled={submitting} type="submit">{submitting ? '처리 중…' : '회원가입'}</button>
      </form>
      <a className="auth-switch-link" href="#/auth">이미 계정이 있으신가요? <u>로그인</u></a>
    </section>
  );
}

export function App() {
  const [{ route, arg }, navigate] = useHashRoute();
  const today = useSeoulToday();
  const [me, setMe] = useState(null);
  const [gameQuery, setGameQuery] = useState('');
  const [roomQuery, setRoomQuery] = useState('');
  const [roomType, setRoomType] = useState('');
  const [roomFilters, setRoomFilters] = useState(EMPTY_ROOM_FILTERS);
  const [myTab, setMyTab] = useState('joined');
  const [createMode, setCreateMode] = useState('GAME_FOCUSED');
  const [createGame, setCreateGame] = useState(null);
  const [dataVersion, setDataVersion] = useState(0);
  const [notificationOpen, setNotificationOpen] = useState(false);
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
    setNotificationOpen(false);
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
    panelOpen: notificationOpen,
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

  const refreshData = () => setDataVersion((version) => version + 1);

  useEffect(() => () => window.clearTimeout(toastTimer.current), []);
  useEffect(() => {
    setUnauthenticatedHandler(expireAuthentication);
    return () => setUnauthenticatedHandler(undefined);
  }, [expireAuthentication]);
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
  useEffect(() => {
    window.scrollTo(0, 0);
    setNotificationOpen(false);
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

  const handleBrowsePeople = () => setRoomType('PERSON_FOCUSED');

  const handleSearchGame = (keyword) => {
    setGameQuery(keyword);
    navigate('/game-list');
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
      navigate('/home');
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
      setNotificationOpen(false);
      refreshData();
      showToast('로그아웃했어요.');
      navigate('/home');
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
      navigate('/session/' + room.id);
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
      navigate('/session/' + roomId);
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

  let content;
  if (route === 'find') content = <FindRoomsView roomType={roomType} onRoomTypeChange={setRoomType} roomQuery={roomQuery} onRoomQueryChange={setRoomQuery} roomFilters={roomFilters} onRoomFiltersChange={setRoomFilters} dataVersion={dataVersion} />;
  else if (route === 'game-list' && arg === 'played') {
    content = me
      ? <GamesView title="해 본 게임" gameQuery={gameQuery} onGameQueryChange={setGameQuery} dataVersion={dataVersion} onPlayedError={handleProtectedError}
        initialFilters={{ ...EMPTY_GAME_FILTERS, playedFilter: 'PLAYED_ONLY' }} />
      : <LoginRequiredView message="해 본 게임을 보려면 로그인해주세요." />;
  }
  else if (route === 'game-list') content = <GamesView title="게임 찾기" gameQuery={gameQuery} onGameQueryChange={setGameQuery} dataVersion={dataVersion} onPlayedError={handleProtectedError} />;
  else if (route === 'game') content = <GameDetailView gameId={arg} onCreateGame={handleCreateGame} dataVersion={dataVersion} onPlayedError={handleProtectedError} renderRoom={(room) => <SessionCard key={room.id} room={room} />} />;
  else if (route === 'session') content = <SessionDetailView sessionId={arg} me={me} onApply={handleApply} onCancelApply={handleCancelApply} onHostCancel={handleHostCancel} onFinish={handleFinish} dataVersion={dataVersion} />;
  else if (route === 'create') content = me ? <CreateView createMode={createMode} onCreateModeChange={setCreateMode} initialGame={createGame} onCreate={handleCreate} today={today} /> : <LoginRequiredView message="모임을 만들려면 로그인해주세요." />;
  else if (route === 'edit') content = me ? <EditView sessionId={arg} onSave={handleSave} dataVersion={dataVersion} today={today} /> : <LoginRequiredView message="모임을 수정하려면 로그인해주세요." />;
  else if (route === 'my') content = me ? <MyRoomsSection myTab={myTab} onMyTabChange={setMyTab} dataVersion={dataVersion} onCancelApply={handleCancelApply} /> : <LoginRequiredView message="내 모임을 보려면 로그인해주세요." />;
  else if (route === 'chat') content = me ? <ChatRoomView roomId={arg} dataVersion={dataVersion} me={me} /> : <LoginRequiredView message="모임 채팅을 보려면 로그인해주세요." />;
  else if (route === 'chats') content = me ? <ChatListView dataVersion={dataVersion} /> : <LoginRequiredView message="채팅 목록을 보려면 로그인해주세요." />;
  else if (route === 'profile') content = me ? <ProfileView me={me} onSave={handleSaveProfile} onLogout={handleLogout} socialProviders={socialProviders} onSocialLink={handleSocialLink} onUploadImage={handleUploadProfileImage} onDeleteImage={handleDeleteProfileImage} /> : <LoginRequiredView message="마이페이지를 보려면 로그인해주세요." />;
  else if (route === 'auth') content = me ? <div className="card"><h2>이미 로그인되어 있어요.</h2><a className="btn" href="#/home">홈으로 이동</a></div> : <AuthView onLogin={handleLogin} socialProviders={socialProviders} onSocialLogin={handleSocialLogin} />;
  else if (route === 'signup') content = me ? <div className="card"><h2>이미 로그인되어 있어요.</h2><a className="btn" href="#/home">홈으로 이동</a></div> : <SignupView onSignup={handleSignup} />;
  else content = <HomeView onBrowsePeople={handleBrowsePeople} onSearchGame={handleSearchGame} dataVersion={dataVersion} />;

  return (
    <>
      <Header
        route={route}
        me={me}
        notificationMenu={{
          open: notificationOpen,
          unreadCount: notificationReadSync.visibleUnreadCount,
          notifications: notificationState.notifications,
          listStatus: notificationState.listStatus,
          optimisticReadIds: notificationReadSync.optimisticReadIds,
          bulkReadPending: notificationReadSync.bulkReadPending,
          synchronizationFailed: notificationReadSync.synchronizationFailed,
          onToggle: () => setNotificationOpen((open) => !open),
          onClose: () => setNotificationOpen(false),
          onRetry: notificationState.retry,
          onSelectNotification: handleNotificationSelect,
          onMarkAllAsRead: notificationReadSync.markAllAsRead,
          onRetrySynchronization: notificationReadSync.retrySynchronization
        }}
      />
      <main>{content}</main>
      <SiteFooter />
      <ScrollToTopButton />
      <div id="toast" role="status" aria-live="polite" className={(toast.message ? 'show ' : '') + (toast.type === 'err' ? 'err' : '')}>{toast.message}</div>
    </>
  );
}

const rootElement = document.getElementById('root');
if (rootElement) createRoot(rootElement).render(<App />);
