import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import brandSymbol from '../assets/albam-mate-symbol.png';
import './styles.css';

const MOCK_NOW = new Date('2026-07-23T12:00:00+09:00');
const MOCK_NOW_MS = MOCK_NOW.getTime();
const SESSION_FINISH_AFTER_MS = 24 * 60 * 60 * 1000;
const MOCK_TODAY = '2026-07-23';
const DEFAULT_ROOM_DATE = '2026-07-26';
const WEEKDAY_LABELS = ['일', '월', '화', '수', '목', '금', '토'];
const EXP_LABEL = {
  ALL_LEVELS: '경험 무관',
  BEGINNER_WELCOME: '초보 환영',
  EXPERIENCED_PREFERRED: '경험자 위주'
};
const CAPACITY_OPTIONS = Array.from({ length: 10 }, (_, index) => index + 1);
const GAME_SEARCH_PAGE_SIZE = 10;
const GAME_SEARCH_DEBOUNCE_MS = 250;
const GAME_API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '');

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

function formatRoomDate(isoDate) {
  const parts = dateParts(isoDate);
  if (!parts) return '';
  const weekday = new Date(Date.UTC(parts.year, parts.monthIndex, parts.day)).getUTCDay();
  return (parts.monthIndex + 1) + '/' + parts.day + '(' + WEEKDAY_LABELS[weekday] + ')';
}

function sessionDateLabel(isoDate) {
  return isoDate === MOCK_TODAY ? '오늘' : formatRoomDate(isoDate);
}

function formatCalendarDate(isoDate) {
  const parts = dateParts(isoDate);
  if (!parts) return '';
  const weekday = new Date(Date.UTC(parts.year, parts.monthIndex, parts.day)).getUTCDay();
  return parts.year + '년 ' + (parts.monthIndex + 1) + '월 ' + parts.day + '일 (' + WEEKDAY_LABELS[weekday] + ')';
}

function monthFromIsoDate(isoDate) {
  const parts = dateParts(isoDate) || dateParts(DEFAULT_ROOM_DATE);
  return new Date(parts.year, parts.monthIndex, 1);
}

function formDateFromSession(session) {
  const isoDate = session?.startsAt?.slice(0, 10);
  return dateParts(isoDate) ? isoDate : DEFAULT_ROOM_DATE;
}

const GAMES = [
  { id: 'g1', title: '테라포밍 마스', englishName: 'Terraforming Mars', emoji: '🚀', players: '1~5명', time: '120분', complexity: '헤비', tag: '전략' },
  { id: 'g2', title: '아즈울', englishName: 'Azul', emoji: '🀄', players: '2~4명', time: '45분', complexity: '입문', tag: '타일 배치' },
  { id: 'g3', title: '스플렌더', englishName: 'Splendor', emoji: '💎', players: '2~4명', time: '30분', complexity: '입문', tag: '엔진 빌딩' },
  { id: 'g4', title: '클루', englishName: 'Clue', emoji: '🕵️', players: '3~6명', time: '60분', complexity: '입문', tag: '추리' },
  { id: 'g5', title: '스컬킹', englishName: 'Skull King', emoji: '🏴‍☠️', players: '2~6명', time: '30분', complexity: '입문', tag: '트릭 테이킹' },
  { id: 'g6', title: '아그리콜라', englishName: 'Agricola', emoji: '🌾', players: '1~4명', time: '150분', complexity: '헤비', tag: '워커 플레이스먼트' },
  { id: 'g7', title: '뱅!', englishName: 'BANG!', emoji: '🤠', players: '4~7명', time: '40분', complexity: '입문', tag: '파티' },
  { id: 'g8', title: '윙스팬', englishName: 'Wingspan', emoji: '🐦', players: '1~5명', time: '70분', complexity: '중급', tag: '엔진 빌딩' },
  { id: 'g9', title: '카탄', englishName: 'CATAN', emoji: '🏝️', players: '3~4명', time: '90분', complexity: '중급', tag: '협상' },
  { id: 'g10', title: '아발론', englishName: 'The Resistance: Avalon', emoji: '⚔️', players: '5~10명', time: '30분', complexity: '입문', tag: '정체 은닉' }
];

function normalizeGameSummary(game) {
  return {
    id: String(game.id),
    title: game.name || game.title || '이름 없는 게임',
    englishName: game.englishName || '',
    emoji: game.emoji || '🎲',
    players: game.recommendedPlayerCount || game.players || '',
    time: game.estimatedPlayTime || game.time || '',
    tag: game.tag || ''
  };
}

function mockGameSearchPage(keyword, page, size) {
  const query = keyword.trim().toLowerCase();
  const matches = GAMES.filter((game) => [game.title, game.englishName].some((value) => value.toLowerCase().includes(query)));
  const offset = page * size;
  return {
    content: matches.slice(offset, offset + size).map(normalizeGameSummary),
    page,
    size,
    totalElements: matches.length,
    totalPages: Math.ceil(matches.length / size),
    hasNext: offset + size < matches.length
  };
}

async function fetchGameSearchPage(keyword, page, size, signal) {
  if (!GAME_API_BASE_URL) return mockGameSearchPage(keyword, page, size);

  const params = new URLSearchParams({ keyword, page: String(page), size: String(size) });
  const response = await fetch(GAME_API_BASE_URL + '/api/games?' + params, { signal });
  if (!response.ok) throw new Error('게임 목록을 불러오지 못했어요.');

  const payload = await response.json();
  const data = payload.data;
  return { ...data, content: (data.content || []).map(normalizeGameSummary) };
}

const INITIAL_SESSIONS = [
  { id: 's1', sessionType: 'GAME_FOCUSED', title: '토요일 오후 테라포밍 마스', description: '첫 판도 편하게 진행해요.', gameId: 'g1', host: '보드왕', isRulemasterLed: true, time: '14:00', startsAt: '2026-07-25T14:00:00+09:00', region: '홍대', place: '다이스캐슬 보드게임카페', recruitmentCapacity: 4, experienceLevel: 'BEGINNER_WELCOME', status: 'RECRUITING', ps: [{ n: '미플러', st: 'ACTIVE' }, { n: '초보새싹', st: 'ACTIVE' }] },
  { id: 's2', sessionType: 'GAME_FOCUSED', title: '퇴근 후 가볍게 스컬킹', description: '한두 판 즐기고 갈 분을 찾아요.', gameId: 'g5', host: '스컬장인', isRulemasterLed: false, time: '19:30', startsAt: '2026-07-23T19:30:00+09:00', region: '홍대', place: '홍대입구역 인근 보드게임카페', recruitmentCapacity: 5, experienceLevel: 'ALL_LEVELS', status: 'RECRUITING', ps: [{ n: '하트조커', st: 'ACTIVE' }, { n: '제로콜라', st: 'ACTIVE' }, { n: '딜러왕', st: 'ACTIVE' }] },
  { id: 's3', sessionType: 'PERSON_FOCUSED', title: '주말 오후 같이 게임 고를 분', description: '게임은 카페에서 함께 정해요.', gameId: null, host: '주말보더', isRulemasterLed: false, time: '15:00', startsAt: '2026-07-26T15:00:00+09:00', region: '홍대', place: '홍대입구역 근처 보드게임카페', recruitmentCapacity: 4, experienceLevel: 'BEGINNER_WELCOME', status: 'RECRUITING', ps: [{ n: '김보드', st: 'ACTIVE' }, { n: '토요미플', st: 'ACTIVE' }] },
  { id: 's4', sessionType: 'PERSON_FOCUSED', title: '무거운 전략 게임 함께 즐길 분', description: '게임 선택은 참여자와 상의합니다.', gameId: 'g6', host: '동탄미플', isRulemasterLed: false, time: '14:00', startsAt: '2026-07-27T14:00:00+09:00', region: '홍대', place: '홍대입구역 인근 보드게임카페', recruitmentCapacity: 6, experienceLevel: 'EXPERIENCED_PREFERRED', status: 'RECRUITING', ps: [{ n: '전략가A', st: 'ACTIVE' }, { n: '유로게이머', st: 'ACTIVE' }] },
  { id: 's5', sessionType: 'GAME_FOCUSED', title: '아침 윙스팬 모임', description: '윙스팬 좋아하는 분들과 아침 모임!', gameId: 'g8', host: '한예진', isRulemasterLed: false, time: '11:00', startsAt: '2026-07-23T11:00:00+09:00', region: '홍대', place: '홍대입구역 인근 보드게임카페', recruitmentCapacity: 4, experienceLevel: 'BEGINNER_WELCOME', status: 'CLOSED', ps: [{ n: '버드워처', st: 'ACTIVE' }, { n: '알둥지', st: 'ACTIVE' }, { n: '깃털수집가', st: 'ACTIVE' }, { n: '늦은새', st: 'ACTIVE' }] },
  { id: 's6', sessionType: 'PERSON_FOCUSED', title: '지난주 아즈울 모임', description: '함께 게임을 정해 즐긴 모임입니다.', gameId: 'g2', host: '모임장수', isRulemasterLed: false, time: '14:00', startsAt: '2026-07-19T14:00:00+09:00', region: '홍대', place: '홍대입구역 인근 보드게임카페', recruitmentCapacity: 4, experienceLevel: 'BEGINNER_WELCOME', status: 'FINISHED', ps: [{ n: '한예진', st: 'ACTIVE' }, { n: '타일러', st: 'ACTIVE' }, { n: '봄버맨', st: 'CANCELED' }] },
  { id: 's7', sessionType: 'GAME_FOCUSED', title: '오늘 저녁 아발론 풀방 도전', description: '정체 은닉 게임을 함께 즐겨요.', gameId: 'g10', host: '원탁기사', isRulemasterLed: true, time: '19:00', startsAt: '2026-07-23T19:00:00+09:00', region: '홍대', place: '다이스캐슬 보드게임카페', recruitmentCapacity: 7, experienceLevel: 'ALL_LEVELS', status: 'CLOSED', ps: [{ n: '멀린', st: 'ACTIVE' }, { n: '퍼시벌', st: 'ACTIVE' }, { n: '모르가나', st: 'ACTIVE' }, { n: '암살자요정', st: 'ACTIVE' }, { n: '충신하나', st: 'ACTIVE' }, { n: '충신둘', st: 'ACTIVE' }, { n: '미니언', st: 'ACTIVE' }] }
];

function cloneInitialSessions() {
  return INITIAL_SESSIONS.map((session) => ({
    ...session,
    date: sessionDateLabel(session.startsAt.slice(0, 10)),
    ps: session.ps.map((participant) => ({ ...participant }))
  }));
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

function activeParticipantCount(session) {
  return session.ps.filter((participant) => participant.st === 'ACTIVE').length;
}

function participantCount(session) {
  return 1 + activeParticipantCount(session);
}

function remainingRecruitmentSeats(session) {
  return Math.max(0, session.recruitmentCapacity - activeParticipantCount(session));
}

function myEntry(session, me) {
  return session.ps.find((participant) => participant.n === me);
}

function hasStarted(session) {
  return MOCK_NOW_MS >= Date.parse(session.startsAt);
}

function elapsedSinceStartMs(session) {
  return MOCK_NOW_MS - Date.parse(session.startsAt);
}

function sessionStatus(session) {
  if (session.status === 'CANCELED' || session.status === 'FINISHED') return session.status;
  let status = session.status;
  if (status === 'RECRUITING' && (remainingRecruitmentSeats(session) === 0 || hasStarted(session))) status = 'CLOSED';
  if (status === 'CLOSED' && elapsedSinceStartMs(session) >= SESSION_FINISH_AFTER_MS) status = 'FINISHED';
  return status;
}

function statusMeta(session) {
  const labels = {
    RECRUITING: ['모집 중', 'green'],
    CLOSED: ['모집 마감', 'amber'],
    CANCELED: ['취소됨', 'red'],
    FINISHED: ['종료됨', 'gray']
  };
  const status = sessionStatus(session);
  const entry = labels[status];
  return { code: status, label: entry[0], className: entry[1] };
}

function isUpcoming(session) {
  return ['RECRUITING', 'CLOSED'].includes(sessionStatus(session));
}

function isFutureUpcoming(session) {
  return isUpcoming(session) && Date.parse(session.startsAt) > MOCK_NOW_MS;
}

function canViewPrivate(session, me) {
  return session.host === me || myEntry(session, me)?.st === 'ACTIVE';
}

function canJoin(session, me) {
  return sessionStatus(session) === 'RECRUITING'
    && !hasStarted(session)
    && remainingRecruitmentSeats(session) > 0
    && session.host !== me
    && myEntry(session, me)?.st !== 'ACTIVE';
}

function canEdit(session, me) {
  return session.host === me
    && sessionStatus(session) === 'RECRUITING'
    && !hasStarted(session)
    && activeParticipantCount(session) === 0;
}

function gameById(id) {
  return GAMES.find((game) => String(game.id) === String(id));
}

function gameForSession(session) {
  return session.selectedGame || gameById(session.gameId);
}

function startsAtFromDateAndTime(date, time) {
  return dateParts(date) && /^\d{2}:\d{2}$/.test(time) ? date + 'T' + time + ':00+09:00' : null;
}

function roomFormFromSession(session) {
  return {
    gameId: session?.gameId || '',
    selectedGame: session?.selectedGame || (session?.gameId ? gameById(session.gameId) : null),
    title: session?.title || '',
    description: session?.description || '',
    date: formDateFromSession(session),
    time: session?.time || '14:00',
    region: session?.region || '홍대',
    place: session?.place || '',
    recruitmentCapacity: session?.recruitmentCapacity || 4,
    experienceLevel: session?.experienceLevel || 'BEGINNER_WELCOME',
    isRulemasterLed: session?.isRulemasterLed || false
  };
}

function validateRoomForm(form, sessionType) {
  const selectedDate = dateParts(form.date) ? form.date : '';
  const room = {
    ...form,
    gameId: form.gameId || null,
    title: form.title.trim(),
    description: form.description.trim(),
    date: sessionDateLabel(selectedDate),
    region: form.region || '홍대',
    place: form.place.trim(),
    recruitmentCapacity: Number(form.recruitmentCapacity),
    startsAt: startsAtFromDateAndTime(selectedDate, form.time)
  };

  if (sessionType === 'GAME_FOCUSED' && !room.gameId) return { error: '게임 중심 모임은 게임을 꼭 선택해야 해요.' };
  if (!room.title) return { error: '모임 제목을 입력해주세요.' };
  if (room.description.length > 50) return { error: '설명은 50자 이내로 입력해주세요.' };
  if (!room.place) return { error: '장소를 입력해주세요.' };
  if (!room.startsAt || Date.parse(room.startsAt) <= MOCK_NOW_MS) return { error: '시작 시간은 목업 기준 현재 시각 이후여야 해요.' };
  if (!Number.isInteger(room.recruitmentCapacity) || room.recruitmentCapacity < 1 || room.recruitmentCapacity > 10) return { error: '모집 정원은 본인 제외 1~10명이어야 해요.' };
  return { room };
}

function Header({ route, me, gameQuery, onGameQueryChange, onSearch }) {
  const rootRoute = { games: 'games', game: 'games', people: 'people', create: 'create', edit: 'my', my: 'my', profile: 'profile' };
  return (
    <header>
      <div className="hwrap">
        <a className="logo" href="#/home" aria-label="알밤메이트 홈">
          <span className="brand-mark" aria-hidden="true"><img src={brandSymbol} alt="" /></span>
          <span className="brand-wordmark"><span className="brand-name">알밤</span><span className="brand-mate">메이트</span></span>
        </a>
        <form className="searchbox" role="search" onSubmit={(event) => { event.preventDefault(); onSearch(); }}>
          <input aria-label="게임 이름 검색" placeholder="게임 이름으로 검색" value={gameQuery} onChange={(event) => onGameQueryChange(event.target.value)} />
          <button type="submit">검색</button>
        </form>
        <nav id="gnb" aria-label="주요 메뉴">
          <a href="#/games" className={rootRoute[route] === 'games' ? 'on' : ''}>게임 찾기</a>
          <a href="#/people" className={rootRoute[route] === 'people' ? 'on' : ''}>사람 중심 모임</a>
          <a href="#/create" className={rootRoute[route] === 'create' ? 'on' : ''}>모임 만들기</a>
          <a href="#/my" className={rootRoute[route] === 'my' ? 'on' : ''}>내 모임</a>
          <a href="#/profile" className={'profile-chip ' + (rootRoute[route] === 'profile' ? 'on' : '')}>{me}</a>
        </nav>
      </div>
    </header>
  );
}

function SeatIcons({ session }) {
  const active = activeParticipantCount(session);
  return (
    <>
      {Array.from({ length: active }, (_, index) => <span className="seat f" key={'filled-' + index} />)}
      {Array.from({ length: Math.max(0, session.recruitmentCapacity - active) }, (_, index) => <span className="seat" key={'empty-' + index} />)}
    </>
  );
}

function SessionCard({ session }) {
  const game = session.gameId ? gameForSession(session) : null;
  const status = statusMeta(session);
  const active = activeParticipantCount(session);
  return (
    <a className="scard" href={'#/session/' + session.id}>
      <div className="scard-top">
        <span className="gemoji">{game ? game.emoji : '🙌'}</span>
        <div>
          <div className="stitle">
            {session.title} <span className={'badge ' + (session.sessionType === 'PERSON_FOCUSED' ? 'people' : 'game')}>{session.sessionType === 'PERSON_FOCUSED' ? '사람 중심' : '게임 중심'}</span>{' '}
            <span className={'badge ' + status.className}>{status.label}</span>
          </div>
          <div className="smeta">{game ? game.emoji + ' ' + game.title : '게임은 모임에서 정해요'} · {session.date} {session.time} · {session.region || '홍대'}</div>
        </div>
      </div>
      <div className="srow"><SeatIcons session={session} /><span className="cap">모집 {active}/{session.recruitmentCapacity}명 · 총 {participantCount(session)}/{session.recruitmentCapacity + 1}명</span></div>
      <div className="sfoot"><span className="chip">{EXP_LABEL[session.experienceLevel]}</span><span>{session.isRulemasterLed ? '룰마스터 진행' : '참가자끼리 진행'}</span><span>상세 위치는 참가 후 확인</span></div>
    </a>
  );
}

function GameCard({ game, upcomingCount }) {
  return (
    <a className="gcard" href={'#/game/' + game.id}>
      <div className="gart">{game.emoji}</div>
      <div className="gtitle">{game.title}</div>
      <div className="gen">{game.englishName}</div>
      <div className="gmeta">{game.players} · {game.time} · {game.complexity}</div>
      <span className="chip">{game.tag}</span>
      <div className="gsess">예정 모임 {upcomingCount}개</div>
    </a>
  );
}

function HomeView({ personCount }) {
  return (
    <section className="card hero">
      <h1>오늘, 보드게임 한 판 어때요? 🎲</h1>
      <p>게임을 먼저 고르거나, 함께할 사람부터 찾아 모임을 만들 수 있어요.</p>
      <div className="dual">
        <a className="entry gamefirst" href="#/games"><span className="big">🎲</span><h3>게임부터 찾기</h3><p>하고 싶은 게임을 검색하고, 그 게임의 공개 모임을 찾아보세요.</p><span className="sub">게임 이름으로 검색 →</span></a>
        <a className="entry peoplefirst" href="#/people"><span className="big">🙌</span><h3>사람부터 만나기</h3><p>게임이 아직 정해지지 않아도 괜찮아요. 제목으로 원하는 모임을 찾아보세요.</p><span className="sub">공개 모임 {personCount}개 →</span></a>
      </div>
    </section>
  );
}

function GamesView({ gameQuery, sessions }) {
  const query = gameQuery.toLowerCase();
  const games = GAMES.filter((game) => !query || game.title.toLowerCase().includes(query));
  return (
    <>
      <h2>🎲 게임 찾기 <span className="cnt">{games.length}개{gameQuery ? ' · \'' + gameQuery + '\' 검색 결과' : ''}</span></h2>
      <p className="hint" style={{ margin: '-8px 0 15px' }}>게임 이름의 부분 일치 검색만 제공해요.</p>
      <div className="grid cols3">
        {games.map((game) => <GameCard key={game.id} game={game} upcomingCount={sessions.filter((session) => session.sessionType === 'GAME_FOCUSED' && session.gameId === game.id && isFutureUpcoming(session)).length} />)}
      </div>
      {!games.length && <div className="infobox" style={{ marginTop: 14 }}>검색 결과가 없어요. 다른 게임 이름으로 다시 찾아보세요.</div>}
    </>
  );
}

function PeopleView({ sessions, peopleQuery, onPeopleQueryChange }) {
  const [input, setInput] = useState(peopleQuery);
  const keyword = peopleQuery.toLowerCase();
  const rooms = sessions.filter((session) => session.sessionType === 'PERSON_FOCUSED' && isUpcoming(session) && (!keyword || session.title.toLowerCase().includes(keyword)));
  return (
    <>
      <h2>🙌 사람 중심 모임 찾기 <span className="cnt">{rooms.length}개</span></h2>
      <form className="inline-search" onSubmit={(event) => { event.preventDefault(); onPeopleQueryChange(input.trim()); }}>
        <label className="hint" htmlFor="people-q" style={{ position: 'absolute', left: -9999 }}>사람 중심 모임 제목 검색</label>
        <input id="people-q" value={input} onChange={(event) => setInput(event.target.value)} placeholder="모임 제목으로 검색" />
        <button className="btn" type="submit">검색</button>
      </form>
      <p className="hint" style={{ marginTop: -10, marginBottom: 15 }}>사람 중심 모임은 제목의 부분 일치 검색만 제공해요.</p>
      <div className="grid cols2">{rooms.map((session) => <SessionCard key={session.id} session={session} />)}</div>
      {!rooms.length && <div className="infobox">일치하는 공개 모임이 없어요. 직접 모임을 열어보세요.</div>}
    </>
  );
}

function GameDetailView({ gameId, sessions, onCreateGame }) {
  const game = gameById(gameId);
  if (!game) return <div className="card">게임을 찾을 수 없어요.</div>;
  const rooms = sessions.filter((session) => session.sessionType === 'GAME_FOCUSED' && session.gameId === game.id && isFutureUpcoming(session));
  return (
    <>
      <div className="card">
        <div className="detail-head">
          <div className="dart">{game.emoji}</div>
          <div>
            <h2>{game.title}</h2>
            <div className="gen">{game.englishName}</div>
            <div className="gmeta" style={{ fontSize: 14 }}>{game.players} · 약 {game.time} · {game.complexity}</div>
            <span className="chip">{game.tag}</span>
            <div style={{ marginTop: 15 }}><button className="btn" type="button" onClick={() => onCreateGame(game.id)}>이 게임으로 모임 만들기</button></div>
          </div>
        </div>
      </div>
      <section>
        <h2>📅 예정 모임 <span className="cnt">{rooms.length}개</span></h2>
        {rooms.length ? <div className="grid cols2">{rooms.map((session) => <SessionCard key={session.id} session={session} />)}</div> : <div className="infobox">아직 공개 예정 모임이 없어요. 첫 모임을 만들어보세요.</div>}
      </section>
    </>
  );
}

function SessionActions({ session, me, onApply, onCancelApply, onHostCancel, onFinish }) {
  const status = sessionStatus(session);
  if (session.host === me) {
    if (status === 'RECRUITING') {
      return (
        <>
          <div className="page-actions">{canEdit(session, me) && <a className="btn ghost" href={'#/edit/' + session.id}>모임 수정</a>}<button className="btn redline" type="button" onClick={() => onHostCancel(session.id)}>모임 취소</button></div>
          {canEdit(session, me) && <p className="hint">시작 전이며 다른 활성 참가자가 없을 때만 수정할 수 있어요.</p>}
        </>
      );
    }
    if (status === 'CLOSED') {
      return hasStarted(session)
        ? <div className="page-actions"><button className="btn green" type="button" onClick={() => onFinish(session.id)}>모임 종료</button><button className="btn redline" type="button" onClick={() => onHostCancel(session.id)}>모임 취소</button></div>
        : <button className="btn redline" type="button" onClick={() => onHostCancel(session.id)}>모임 취소</button>;
    }
    return <div className="infobox gray">{status === 'FINISHED' ? '종료된 모임입니다.' : '취소된 모임입니다.'}</div>;
  }
  if (status === 'CANCELED' || status === 'FINISHED') return <div className="infobox gray">{status === 'CANCELED' ? '취소된 모임입니다.' : '종료된 모임입니다.'}</div>;
  if (myEntry(session, me)?.st === 'ACTIVE') {
    return status === 'RECRUITING' && !hasStarted(session)
      ? <><div className="infobox green">🎉 참가 중입니다.</div><button className="btn ghost big" style={{ marginTop: 9 }} type="button" onClick={() => onCancelApply(session.id)}>참가 취소</button></>
      : <div className="infobox green">🎉 참가 중입니다.</div>;
  }
  if (canJoin(session, me)) return <button className="btn big" type="button" onClick={() => onApply(session.id)}>🙋 참가 신청하기</button>;
  return <div className="infobox amber">모집이 마감되어 더 이상 참가할 수 없어요.</div>;
}

function SessionDetailView({ sessionId, sessions, me, onApply, onCancelApply, onHostCancel, onFinish }) {
  const session = sessions.find((item) => item.id === sessionId);
  if (!session) return <div className="card">모임을 찾을 수 없어요.</div>;
  const status = statusMeta(session);
  const privateView = canViewPrivate(session, me);
  if (['CANCELED', 'FINISHED'].includes(status.code) && !privateView) return <div className="card">모임을 찾을 수 없어요.</div>;
  const game = session.gameId ? gameForSession(session) : null;
  const banners = {
    RECRUITING: ['green', '✅ 참가 신청을 받는 중입니다'],
    CLOSED: ['amber', '⏳ 모집이 마감되었습니다'],
    CANCELED: ['red', '❌ 주최자가 취소한 모임입니다'],
    FINISHED: ['gray', '🏁 종료된 모임입니다']
  };
  const banner = banners[status.code];
  const active = activeParticipantCount(session);
  return (
    <>
      <div className={'banner ' + banner[0]}>{banner[1]}</div>
      <div className="layout wide">
        <div>
          <div className="card">
            <div className="detail-head">
              <div className="dart">{game ? game.emoji : '🙌'}</div>
              <div style={{ flex: 1 }}>
                <h2>{session.title} <span className={'badge ' + (session.sessionType === 'PERSON_FOCUSED' ? 'people' : 'game')}>{session.sessionType === 'PERSON_FOCUSED' ? '사람 중심' : '게임 중심'}</span></h2>
                <p className="smeta">{game ? game.emoji + ' ' + game.title : '게임은 모임에서 정해요'}</p>
                {session.description && <p style={{ color: 'var(--brown2)', marginTop: 10 }}>{session.description}</p>}
                <table className="metatable"><tbody>
                  <tr><td>일시</td><td>{session.date} {session.time}</td></tr>
                  {privateView
                    ? <><tr><td>장소</td><td>{session.region || '홍대'} · {session.place}</td></tr><tr><td>주최자</td><td>{session.host}{session.host === me ? ' (나)' : ''}</td></tr></>
                    : <tr><td>장소</td><td>{session.region || '홍대'} · 참가 확정 후 확인할 수 있어요.</td></tr>}
                  <tr><td>정원</td><td>모집 {active}/{session.recruitmentCapacity}명 · 총 {participantCount(session)}/{session.recruitmentCapacity + 1}명</td></tr>
                  <tr><td>경험 수준</td><td>{EXP_LABEL[session.experienceLevel]}</td></tr>
                  <tr><td>진행</td><td>{session.isRulemasterLed ? '룰마스터 진행 (주최자 자기신고)' : '참가자끼리 진행'}</td></tr>
                </tbody></table>
              </div>
            </div>
          </div>
          {privateView
            ? <section><h2>👥 참가자 <span className="cnt">총 {participantCount(session)}/{session.recruitmentCapacity + 1}명</span></h2><div className="card"><div className="srow" style={{ marginTop: 0 }}><SeatIcons session={session} /></div><div><span className="pchip">🙂 {session.host} · 주최자</span>{session.ps.filter((participant) => participant.st === 'ACTIVE').map((participant) => <span className="pchip" key={participant.n}>🙂 {participant.n} · 참가</span>)}{active === 0 && <span className="hint">아직 참가자가 없어요.</span>}</div></div></section>
            : <section><h2>👥 참가자</h2><div className="infobox">정확한 장소와 참가자 목록은 주최자 또는 현재 참가자만 확인할 수 있어요.</div></section>}
        </div>
        <aside><div className="card"><SessionActions session={session} me={me} onApply={onApply} onCancelApply={onCancelApply} onHostCancel={onHostCancel} onFinish={onFinish} /></div></aside>
      </div>
    </>
  );
}

function GamePickerDialog({ isOpen, selectedGameId, allowClear, onSelect, onClear, onClose }) {
  const [query, setQuery] = useState('');
  const [pageData, setPageData] = useState({ content: [], page: 0, size: GAME_SEARCH_PAGE_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const searchInputRef = useRef(null);

  useEffect(() => {
    if (!isOpen) return;
    setQuery('');
    setPageData({ content: [], page: 0, size: GAME_SEARCH_PAGE_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
    setError('');
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen) return undefined;
    const keyword = query.trim();
    if (!keyword) {
      setPageData({ content: [], page: 0, size: GAME_SEARCH_PAGE_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
      setError('');
      setLoading(false);
      return undefined;
    }

    let canceled = false;
    const controller = new AbortController();
    const timer = window.setTimeout(async () => {
      setLoading(true);
      setError('');
      try {
        const result = await fetchGameSearchPage(keyword, 0, GAME_SEARCH_PAGE_SIZE, controller.signal);
        if (!canceled) setPageData({ ...result, content: result.content || [] });
      } catch (requestError) {
        if (!canceled && requestError?.name !== 'AbortError') setError(requestError instanceof Error ? requestError.message : '게임 목록을 불러오지 못했어요.');
      } finally {
        if (!canceled) setLoading(false);
      }
    }, GAME_SEARCH_DEBOUNCE_MS);

    return () => {
      canceled = true;
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [isOpen, query]);

  useEffect(() => {
    if (!isOpen) return undefined;
    const focusTimer = window.setTimeout(() => searchInputRef.current?.focus(), 0);
    const handleKeyDown = (event) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => {
      window.clearTimeout(focusTimer);
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  const hasQuery = Boolean(query.trim());
  const loadMore = async () => {
    if (loading || !pageData.hasNext) return;
    const keyword = query.trim();
    setLoading(true);
    setError('');
    try {
      const nextPage = await fetchGameSearchPage(keyword, pageData.page + 1, GAME_SEARCH_PAGE_SIZE);
      if (query.trim() === keyword) setPageData((current) => ({ ...nextPage, content: [...current.content, ...(nextPage.content || [])] }));
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '게임 목록을 불러오지 못했어요.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="game-picker-backdrop" role="presentation" onMouseDown={onClose}>
      <section className="game-picker" role="dialog" aria-modal="true" aria-labelledby="game-picker-title" onMouseDown={(event) => event.stopPropagation()}>
        <div className="game-picker-head">
          <div><h3 id="game-picker-title">게임 검색</h3><p>게임 이름으로 검색한 결과만 10건씩 불러와요.</p></div>
          <button type="button" className="game-picker-close" aria-label="게임 검색 닫기" onClick={onClose}>×</button>
        </div>
        <div className="game-picker-search"><span className="game-picker-search-label" aria-hidden="true">검색</span><input ref={searchInputRef} value={query} onChange={(event) => setQuery(event.target.value)} placeholder="예: 스플렌더, 테라포밍 마스" aria-label="게임 이름 검색" /></div>
        <div className="game-picker-body">
          {!hasQuery && <div className="game-search-empty">게임 이름을 입력하면 목록 API에서 검색 결과를 불러와요.</div>}
          {hasQuery && !error && <p className="game-search-count">{loading && !pageData.content.length ? '검색 중…' : '검색 결과 ' + pageData.totalElements + '개'}</p>}
          {error && <div className="game-search-error">{error}</div>}
          {!loading && hasQuery && !error && !pageData.content.length && <div className="game-search-empty">일치하는 게임이 없어요. 다른 이름으로 검색해보세요.</div>}
          {!!pageData.content.length && <div className="game-search-results">{pageData.content.map((game) => <button type="button" className={'game-search-result ' + (String(game.id) === String(selectedGameId) ? 'selected' : '')} key={game.id} onClick={() => { onSelect(game); onClose(); }}><span className="game-result-mark" aria-hidden="true">{game.title.slice(0, 1)}</span><span className="game-result-copy"><strong>{game.title}</strong><span>{[game.englishName, game.players, game.time].filter(Boolean).join(' · ')}</span></span><span className="game-result-action">{String(game.id) === String(selectedGameId) ? '선택됨' : '선택'}</span></button>)}</div>}
          {pageData.hasNext && <button type="button" className="game-load-more" disabled={loading} onClick={loadMore}>{loading ? '불러오는 중…' : '검색 결과 더 보기'}</button>}
        </div>
        <div className="game-picker-actions">
          {allowClear && <button type="button" className="game-picker-clear" onClick={() => { onClear(); onClose(); }}>게임 선택 안 함</button>}
          <button type="button" className="btn ghost" onClick={onClose}>닫기</button>
        </div>
      </section>
    </div>
  );
}

function DatePicker({ id, value, onChange }) {
  const selectedDate = dateParts(value) ? value : DEFAULT_ROOM_DATE;
  const [isOpen, setIsOpen] = useState(false);
  const [draftDate, setDraftDate] = useState(selectedDate);
  const [visibleMonth, setVisibleMonth] = useState(() => monthFromIsoDate(selectedDate));
  const pickerRef = useRef(null);
  const triggerRef = useRef(null);

  useEffect(() => {
    if (!isOpen) return undefined;
    const closeWhenOutside = (event) => {
      if (!pickerRef.current?.contains(event.target)) setIsOpen(false);
    };
    const closeOnEscape = (event) => {
      if (event.key === 'Escape') {
        setIsOpen(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener('pointerdown', closeWhenOutside);
    window.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('pointerdown', closeWhenOutside);
      window.removeEventListener('keydown', closeOnEscape);
    };
  }, [isOpen]);

  const openPicker = () => {
    setDraftDate(selectedDate);
    setVisibleMonth(monthFromIsoDate(selectedDate));
    setIsOpen(true);
  };
  const closePicker = (restoreFocus = false) => {
    setIsOpen(false);
    if (restoreFocus) window.setTimeout(() => triggerRef.current?.focus(), 0);
  };
  const moveMonth = (offset) => setVisibleMonth((month) => new Date(month.getFullYear(), month.getMonth() + offset, 1));
  const monthYear = visibleMonth.getFullYear();
  const monthIndex = visibleMonth.getMonth();
  const mockMonth = monthFromIsoDate(MOCK_TODAY);
  const isFirstSelectableMonth = monthYear === mockMonth.getFullYear() && monthIndex === mockMonth.getMonth();
  const monthStartsOn = new Date(monthYear, monthIndex, 1).getDay();
  const days = Array.from({ length: 42 }, (_, index) => {
    const date = new Date(monthYear, monthIndex, index - monthStartsOn + 1);
    const isoDate = isoDateFromParts(date.getFullYear(), date.getMonth(), date.getDate());
    return {
      isoDate,
      day: date.getDate(),
      isCurrentMonth: date.getMonth() === monthIndex,
      isPast: isoDate < MOCK_TODAY,
      weekday: date.getDay()
    };
  });

  return (
    <div className="date-picker" ref={pickerRef}>
      <button
        id={id}
        ref={triggerRef}
        type="button"
        className="date-picker-trigger"
        aria-label={'날짜 ' + formatCalendarDate(selectedDate)}
        aria-expanded={isOpen}
        aria-haspopup="dialog"
        aria-controls={isOpen ? id + '-calendar' : undefined}
        onClick={() => isOpen ? closePicker() : openPicker()}
      >
        <span className="date-picker-value">{formatRoomDate(selectedDate)}</span>
      </button>
      {isOpen && (
        <section id={id + '-calendar'} className="date-picker-popover" role="dialog" aria-label="날짜 선택">
          <div className="date-picker-header">
            <div className="date-picker-month"><strong>{monthYear}년 {monthIndex + 1}월</strong></div>
            <div className="date-picker-navigation">
              <button type="button" aria-label="이전 달" disabled={isFirstSelectableMonth} onClick={() => moveMonth(-1)}>‹</button>
              <button type="button" aria-label="다음 달" onClick={() => moveMonth(1)}>›</button>
              <button type="button" className="date-picker-close" aria-label="날짜 선택 닫기" onClick={() => closePicker(true)}>×</button>
            </div>
          </div>
          <div className="date-picker-weekdays" aria-hidden="true">{WEEKDAY_LABELS.map((weekday, index) => <span className={index === 0 ? 'sun' : index === 6 ? 'sat' : ''} key={weekday}>{weekday}</span>)}</div>
          <div className="date-picker-days">
            {days.map((day) => (
              <button
                type="button"
                key={day.isoDate}
                className={['date-picker-day', !day.isCurrentMonth && 'outside', day.isoDate === MOCK_TODAY && 'today', day.isoDate === draftDate && 'selected', day.weekday === 0 && 'sun', day.weekday === 6 && 'sat'].filter(Boolean).join(' ')}
                aria-label={formatCalendarDate(day.isoDate)}
                aria-pressed={day.isoDate === draftDate}
                disabled={day.isPast}
                onClick={() => {
                  setDraftDate(day.isoDate);
                  if (!day.isCurrentMonth) setVisibleMonth(monthFromIsoDate(day.isoDate));
                }}
              >
                {day.day}
              </button>
            ))}
          </div>
          <div className="date-picker-footer">
            <button type="button" className="date-picker-today" onClick={() => { setDraftDate(MOCK_TODAY); setVisibleMonth(monthFromIsoDate(MOCK_TODAY)); }}>오늘</button>
            <button type="button" className="date-picker-confirm" onClick={() => { onChange(draftDate); closePicker(true); }}>선택 완료</button>
          </div>
        </section>
      )}
    </div>
  );
}

function RoomFormFields({ form, onChange, sessionType, onOpenGamePicker }) {
  const gameFocused = sessionType === 'GAME_FOCUSED';
  const update = (field, value) => onChange({ ...form, [field]: value });
  const selectedGame = form.selectedGame || (form.gameId ? gameById(form.gameId) : null);
  const selectedGameIsInList = selectedGame && GAMES.some((game) => String(game.id) === String(selectedGame.id));
  const selectGame = (gameId) => onChange({ ...form, gameId, selectedGame: gameId ? gameById(gameId) || selectedGame : null });
  return (
    <>
      <section className="form-section" aria-labelledby="room-detail-title">
        <div className="form-section-heading"><h3 id="room-detail-title">모임 내용</h3><p>게임과 모임을 소개할 내용을 적어주세요.</p></div>
        <div className="formrow">
          <div><div className="field-label-row"><label htmlFor="room-game">게임 {gameFocused ? '(필수)' : '(선택)'}</label><button type="button" className="game-search-open" onClick={onOpenGamePicker}>게임 검색</button></div><select id="room-game" value={form.gameId} onChange={(event) => selectGame(event.target.value)}><option value="">게임을 선택하세요</option>{selectedGame && !selectedGameIsInList && <option value={selectedGame.id}>{selectedGame.title}</option>}{GAMES.map((game) => <option value={game.id} key={game.id}>{game.title}</option>)}</select><p className="hint">{gameFocused ? '목록에 없으면 검색으로 찾아 선택해주세요.' : '게임 없이 모임을 만들 수도 있어요.'}</p></div>
          <div><label htmlFor="room-title">모임 제목</label><input id="room-title" maxLength="100" value={form.title} onChange={(event) => update('title', event.target.value)} placeholder="예: 토요일 오후 같이 게임 고를 분" /></div>
        </div>
        <div className="formrow single"><div><label htmlFor="room-description">설명 (선택, 50자 이내)</label><textarea id="room-description" maxLength="50" value={form.description} onChange={(event) => update('description', event.target.value)} placeholder="예: 처음 오신 분도 환영합니다." /></div></div>
      </section>
      <section className="form-section" aria-labelledby="room-schedule-title">
        <div className="form-section-heading"><h3 id="room-schedule-title">일정과 장소</h3><p>현재는 홍대 지역 모임만 열 수 있어요.</p></div>
        <div className="formrow">
          <div><label htmlFor="room-date">날짜</label><DatePicker id="room-date" value={form.date} onChange={(date) => update('date', date)} /></div>
          <div><label htmlFor="room-time">시간</label><select id="room-time" value={form.time} onChange={(event) => update('time', event.target.value)}>{['11:00', '14:00', '15:00', '19:00', '19:30'].map((time) => <option key={time}>{time}</option>)}</select></div>
        </div>
        <div className="formrow">
          <div><label htmlFor="room-region">지역</label><select id="room-region" value={form.region} onChange={(event) => update('region', event.target.value)}><option value="홍대">홍대</option></select></div>
          <div><label htmlFor="room-place">장소</label><input id="room-place" maxLength="255" value={form.place} onChange={(event) => update('place', event.target.value)} placeholder="예: 홍대입구역 인근 OO보드게임카페" /></div>
        </div>
      </section>
      <section className="form-section" aria-labelledby="room-member-title">
        <div className="form-section-heading"><h3 id="room-member-title">함께할 사람</h3><p>주최자는 모집 인원에 포함되지 않아요.</p></div>
        <div className="formrow">
          <div><label htmlFor="room-capacity">모집 정원 (본인 제외, 1~10명)</label><select id="room-capacity" value={form.recruitmentCapacity} onChange={(event) => update('recruitmentCapacity', Number(event.target.value))}>{CAPACITY_OPTIONS.map((capacity) => <option value={capacity} key={capacity}>{capacity}명</option>)}</select></div>
          <div><label htmlFor="room-experience">경험 수준</label><select id="room-experience" value={form.experienceLevel} onChange={(event) => update('experienceLevel', event.target.value)}>{Object.entries(EXP_LABEL).map(([code, label]) => <option value={code} key={code}>{label}</option>)}</select></div>
        </div>
        <label className="checkline"><input type="checkbox" checked={form.isRulemasterLed} onChange={(event) => update('isRulemasterLed', event.target.checked)} /> 룰마스터 진행 (개설자 자기신고)</label>
      </section>
    </>
  );
}

function CreateView({ createMode, onCreateModeChange, initialGame, onCreate }) {
  const [form, setForm] = useState(() => ({ ...roomFormFromSession(), gameId: initialGame || '' }));
  const [gamePickerOpen, setGamePickerOpen] = useState(false);
  const gameFocused = createMode === 'GAME_FOCUSED';
  return (
    <>
      <div className="create-page-heading"><p>모임 개설</p><h2>새 모임 만들기</h2></div>
      <div className="create-layout">
        <form className="create-form" onSubmit={(event) => { event.preventDefault(); onCreate(form); }}>
          <section className="form-section create-mode-section" aria-labelledby="create-type-title">
            <div className="form-section-heading"><h3 id="create-type-title">어떤 모임인가요?</h3><p>모임을 여는 순서만 선택하면 됩니다.</p></div>
            <div className="mode-choice">
              <button type="button" className={'modecard ' + (gameFocused ? 'on' : '')} onClick={() => onCreateModeChange('GAME_FOCUSED')}><span>게임을 먼저 정해요</span><b>게임 중심 모임</b><small>게임 선택이 꼭 필요해요.</small></button>
              <button type="button" className={'modecard ' + (!gameFocused ? 'on' : '')} onClick={() => onCreateModeChange('PERSON_FOCUSED')}><span>사람부터 모아요</span><b>사람 중심 모임</b><small>게임은 나중에 정해도 돼요.</small></button>
            </div>
          </section>
          <RoomFormFields form={form} onChange={setForm} sessionType={createMode} onOpenGamePicker={() => setGamePickerOpen(true)} />
          <button className="btn big create-submit" type="submit">모임 열기</button>
        </form>
        <aside className="create-note" aria-label="개설 전 확인할 내용"><h3>개설 전 확인</h3><ul><li>게임 중심은 게임 선택이 필요해요.</li><li>사람 중심은 게임 없이도 열 수 있어요.</li><li>장소는 홍대 지역 안에서 입력해주세요.</li><li>모집 정원은 주최자 제외 1-10명이에요.</li></ul></aside>
      </div>
      <GamePickerDialog isOpen={gamePickerOpen} selectedGameId={form.gameId} allowClear={!gameFocused} onSelect={(game) => setForm((current) => ({ ...current, gameId: game.id, selectedGame: game }))} onClear={() => setForm((current) => ({ ...current, gameId: '', selectedGame: null }))} onClose={() => setGamePickerOpen(false)} />
    </>
  );
}

function EditSessionForm({ session, me, onSave }) {
  const [form, setForm] = useState(() => roomFormFromSession(session));
  const [gamePickerOpen, setGamePickerOpen] = useState(false);
  if (!canEdit(session, me)) return <div className="card">지금은 이 모임을 수정할 수 없어요.</div>;
  return (
    <>
      <h2>✏️ 모임 수정</h2>
      <form className="card" style={{ maxWidth: 780 }} onSubmit={(event) => { event.preventDefault(); onSave(session.id, form); }}>
        <div className="infobox" style={{ marginBottom: 16 }}>{session.sessionType === 'GAME_FOCUSED' ? '게임 중심' : '사람 중심'} 모임 · 유형과 지역은 수정할 수 없어요.</div>
        <RoomFormFields form={form} onChange={setForm} sessionType={session.sessionType} onOpenGamePicker={() => setGamePickerOpen(true)} />
        <div className="page-actions" style={{ marginTop: 16 }}><button className="btn" type="submit">수정 저장</button><a className="btn ghost" href={'#/session/' + session.id}>취소</a></div>
      </form>
      <GamePickerDialog isOpen={gamePickerOpen} selectedGameId={form.gameId} allowClear={session.sessionType === 'PERSON_FOCUSED'} onSelect={(game) => setForm((current) => ({ ...current, gameId: game.id, selectedGame: game }))} onClear={() => setForm((current) => ({ ...current, gameId: '', selectedGame: null }))} onClose={() => setGamePickerOpen(false)} />
    </>
  );
}

function EditView({ sessionId, sessions, me, onSave }) {
  const session = sessions.find((item) => item.id === sessionId);
  if (!session) return <div className="card">지금은 이 모임을 수정할 수 없어요.</div>;
  return <EditSessionForm key={session.id} session={session} me={me} onSave={onSave} />;
}

function MyView({ sessions, me, myTab, onMyTabChange }) {
  const joined = sessions.filter((session) => myEntry(session, me)?.st === 'ACTIVE' && sessionStatus(session) !== 'CANCELED');
  const hosted = sessions.filter((session) => session.host === me);
  const tab = myTab === 'hosted' ? 'hosted' : 'joined';
  const list = tab === 'joined' ? joined : hosted;
  return (
    <>
      <h2>🗂️ 내 모임</h2>
      <div className="tabs"><button type="button" className={tab === 'joined' ? 'on' : ''} onClick={() => onMyTabChange('joined')}>참가한 모임 ({joined.length})</button><button type="button" className={tab === 'hosted' ? 'on' : ''} onClick={() => onMyTabChange('hosted')}>개설한 모임 ({hosted.length})</button></div>
      {list.length ? <div className="grid cols2">{list.map((session) => <SessionCard key={session.id} session={session} />)}</div> : <div className="infobox">{tab === 'joined' ? '아직 참가한 모임이 없어요.' : '아직 개설한 모임이 없어요.'}</div>}
      <p className="hint" style={{ marginTop: 14 }}>카드는 공개 모임 정보만 표시하고, 정확한 장소와 참가자 목록은 모임 상세에서 권한에 따라 확인할 수 있어요.</p>
    </>
  );
}

function ProfileView({ me, onSave }) {
  const [nickname, setNickname] = useState(me);
  return (
    <>
      <h2>🙂 내 프로필</h2>
      <form className="card" style={{ maxWidth: 560 }} onSubmit={(event) => { event.preventDefault(); onSave(nickname); }}>
        <p className="hint" style={{ margin: '0 0 12px' }}>알밤메이트에서 표시되는 내 닉네임입니다.</p>
        <label htmlFor="profile-nickname">닉네임</label>
        <div className="page-actions"><input id="profile-nickname" maxLength="50" value={nickname} onChange={(event) => setNickname(event.target.value)} /><button className="btn" type="submit">저장</button></div>
      </form>
    </>
  );
}

function App() {
  const [{ route, arg }, navigate] = useHashRoute();
  const [me, setMe] = useState('한예진');
  const [sessions, setSessions] = useState(cloneInitialSessions);
  const [gameQuery, setGameQuery] = useState('');
  const [peopleQuery, setPeopleQuery] = useState('');
  const [myTab, setMyTab] = useState('joined');
  const [createMode, setCreateMode] = useState('GAME_FOCUSED');
  const [createGame, setCreateGame] = useState('');
  const [toast, setToast] = useState({ message: '', type: '' });
  const toastTimer = useRef(null);

  const showToast = (message, type = '') => {
    setToast({ message, type });
    window.clearTimeout(toastTimer.current);
    toastTimer.current = window.setTimeout(() => setToast({ message: '', type: '' }), 2800);
  };

  useEffect(() => () => window.clearTimeout(toastTimer.current), []);
  useEffect(() => {
    window.scrollTo(0, 0);
  }, [route, arg]);

  const findSession = (sessionId) => sessions.find((session) => session.id === sessionId);

  const handleGameSearch = () => {
    setGameQuery((query) => query.trim());
    if (route !== 'games') navigate('/games');
  };

  const handleCreateGame = (gameId) => {
    setCreateGame(gameId);
    setCreateMode('GAME_FOCUSED');
    navigate('/create');
  };

  const handleCreate = (form) => {
    const result = validateRoomForm(form, createMode);
    if (result.error) {
      showToast(result.error, 'err');
      return;
    }
    const nextId = 's' + (Math.max(0, ...sessions.map((session) => Number(session.id.slice(1)))) + 1);
    setSessions((current) => [{ id: nextId, sessionType: createMode, host: me, status: 'RECRUITING', ps: [], ...result.room }, ...current]);
    setCreateGame('');
    showToast('모임이 열렸어요! 참가 신청을 받는 중입니다.');
    navigate('/session/' + nextId);
  };

  const handleSave = (sessionId, form) => {
    const session = findSession(sessionId);
    if (!session || !canEdit(session, me)) {
      showToast('지금은 이 모임을 수정할 수 없어요.', 'err');
      return;
    }
    const result = validateRoomForm(form, session.sessionType);
    if (result.error) {
      showToast(result.error, 'err');
      return;
    }
    setSessions((current) => current.map((item) => item.id === sessionId ? { ...item, ...result.room } : item));
    showToast('모임 정보를 수정했어요.');
    navigate('/session/' + sessionId);
  };

  const handleApply = (sessionId) => {
    const session = findSession(sessionId);
    if (!session) {
      showToast('모임을 찾을 수 없어요.', 'err');
      return;
    }
    if (session.host === me) {
      showToast('주최자는 참가자로 신청할 수 없어요.', 'err');
      return;
    }
    if (hasStarted(session)) {
      showToast('이미 시작된 모임에는 참가할 수 없어요.', 'err');
      return;
    }
    if (sessionStatus(session) !== 'RECRUITING') {
      showToast('모집 중인 모임에만 참가할 수 있어요.', 'err');
      return;
    }
    const entry = myEntry(session, me);
    if (entry?.st === 'ACTIVE') {
      showToast('이미 참가 중인 모임이에요.', 'err');
      return;
    }
    if (remainingRecruitmentSeats(session) <= 0) {
      showToast('모집 인원이 모두 찼어요.', 'err');
      return;
    }
    setSessions((current) => current.map((item) => {
      if (item.id !== sessionId) return item;
      const existing = myEntry(item, me);
      const participants = existing ? item.ps.map((participant) => participant.n === me ? { ...participant, st: 'ACTIVE' } : participant) : [...item.ps, { n: me, st: 'ACTIVE' }];
      const next = { ...item, ps: participants };
      return { ...next, status: remainingRecruitmentSeats(next) === 0 ? 'CLOSED' : next.status };
    }));
    showToast('참가했어요! 내 모임에서 확인할 수 있어요.');
  };

  const handleCancelApply = (sessionId) => {
    const session = findSession(sessionId);
    if (!session) {
      showToast('모임을 찾을 수 없어요.', 'err');
      return;
    }
    if (session.host === me) {
      showToast('주최자는 참가 취소를 할 수 없어요. 모임 자체를 취소해주세요.', 'err');
      return;
    }
    if (hasStarted(session)) {
      showToast('이미 시작된 모임은 참가를 취소할 수 없어요.', 'err');
      return;
    }
    if (['CANCELED', 'FINISHED'].includes(sessionStatus(session))) {
      showToast('종료되거나 취소된 모임은 참가를 취소할 수 없어요.', 'err');
      return;
    }
    const entry = myEntry(session, me);
    if (!entry || entry.st !== 'ACTIVE') {
      showToast('취소할 참가 신청이 없어요.', 'err');
      return;
    }
    setSessions((current) => current.map((item) => {
      if (item.id !== sessionId) return item;
      const next = { ...item, ps: item.ps.map((participant) => participant.n === me ? { ...participant, st: 'CANCELED' } : participant) };
      return { ...next, status: sessionStatus(item) === 'CLOSED' && remainingRecruitmentSeats(next) > 0 ? 'RECRUITING' : next.status };
    }));
    showToast('참가를 취소했어요.');
  };

  const handleHostCancel = (sessionId) => {
    const session = findSession(sessionId);
    if (!session) {
      showToast('모임을 찾을 수 없어요.', 'err');
      return;
    }
    if (session.host !== me) {
      showToast('모임 취소는 주최자만 할 수 있어요.', 'err');
      return;
    }
    if (!['RECRUITING', 'CLOSED'].includes(sessionStatus(session))) {
      showToast('이미 종료되거나 취소된 모임이에요.', 'err');
      return;
    }
    setSessions((current) => current.map((item) => item.id === sessionId ? { ...item, status: 'CANCELED' } : item));
    showToast('모임을 취소했어요.');
  };

  const handleFinish = (sessionId) => {
    const session = findSession(sessionId);
    if (!session) {
      showToast('모임을 찾을 수 없어요.', 'err');
      return;
    }
    if (session.host !== me) {
      showToast('모임 종료는 주최자만 할 수 있어요.', 'err');
      return;
    }
    if (sessionStatus(session) !== 'CLOSED' || !hasStarted(session)) {
      showToast('시작된 모집 마감 모임만 종료할 수 있어요.', 'err');
      return;
    }
    setSessions((current) => current.map((item) => item.id === sessionId ? { ...item, status: 'FINISHED' } : item));
    showToast('모임을 종료했어요.');
  };

  const handleSaveProfile = (nickname) => {
    const nextName = nickname.trim();
    if (!nextName) {
      showToast('닉네임을 입력해주세요.', 'err');
      return;
    }
    const oldName = me;
    setSessions((current) => current.map((session) => ({
      ...session,
      host: session.host === oldName ? nextName : session.host,
      ps: session.ps.map((participant) => participant.n === oldName ? { ...participant, n: nextName } : participant)
    })));
    setMe(nextName);
    showToast('닉네임을 저장했어요.');
  };

  let content;
  if (route === 'games') content = <GamesView gameQuery={gameQuery} sessions={sessions} />;
  else if (route === 'people') content = <PeopleView sessions={sessions} peopleQuery={peopleQuery} onPeopleQueryChange={setPeopleQuery} />;
  else if (route === 'game') content = <GameDetailView gameId={arg} sessions={sessions} onCreateGame={handleCreateGame} />;
  else if (route === 'session') content = <SessionDetailView sessionId={arg} sessions={sessions} me={me} onApply={handleApply} onCancelApply={handleCancelApply} onHostCancel={handleHostCancel} onFinish={handleFinish} />;
  else if (route === 'create') content = <CreateView createMode={createMode} onCreateModeChange={setCreateMode} initialGame={createGame} onCreate={handleCreate} />;
  else if (route === 'edit') content = <EditView sessionId={arg} sessions={sessions} me={me} onSave={handleSave} />;
  else if (route === 'my') content = <MyView sessions={sessions} me={me} myTab={myTab} onMyTabChange={setMyTab} />;
  else if (route === 'profile') content = <ProfileView me={me} onSave={handleSaveProfile} />;
  else content = <HomeView personCount={sessions.filter((session) => session.sessionType === 'PERSON_FOCUSED' && isUpcoming(session)).length} />;

  return (
    <>
      <Header route={route} me={me} gameQuery={gameQuery} onGameQueryChange={setGameQuery} onSearch={handleGameSearch} />
      <main>{content}</main>
      <div id="toast" role="status" aria-live="polite" className={(toast.message ? 'show ' : '') + (toast.type === 'err' ? 'err' : '')}>{toast.message}</div>
    </>
  );
}

createRoot(document.getElementById('root')).render(<App />);
