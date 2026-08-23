import React from 'react';
import { api } from '../api';
import { normalizeGameSummary, normalizeRoom } from '../game';
import { useRequest } from '../shared/async';
import defaultGameCover from '../../assets/default-game-cover.jpg';
import { ArrowIcon, ChatIcon, Cover, ErrorBox, Meeples, MatchIcon, RankSkeletons, RoomSkeletons, SeatCount } from '../shared/ui';

const HOME_LIST_SIZE = 3;
const HOME_STARTER_SIZE = 4;

const SEOUL_TIME_FORMATTER = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false
});

const SEOUL_DATE_FORMATTER = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  month: 'numeric',
  day: 'numeric',
  weekday: 'short'
});

function seoulDayKey(date) {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).format(date);
}

function formatUpcomingStartsAt(startsAt) {
  const date = new Date(startsAt);
  if (Number.isNaN(date.getTime())) return '일시 미정';
  const prefix = seoulDayKey(date) === seoulDayKey(new Date()) ? '오늘' : SEOUL_DATE_FORMATTER.format(date);
  return prefix + ' ' + SEOUL_TIME_FORMATTER.format(date);
}

// 오늘 열리는 모임은 Asia/Seoul 기준 오늘 00:00부터 내일 00:00 전까지다.
function seoulTodayRange() {
  const today = seoulDayKey(new Date());
  const [year, month, day] = today.split('-').map(Number);
  const tomorrow = new Date(Date.UTC(year, month - 1, day + 1));
  const nextKey = tomorrow.toISOString().slice(0, 10);
  return { from: today + 'T00:00:00+09:00', to: nextKey + 'T00:00:00+09:00' };
}

export function nextUpcomingRoom(rooms, now = Date.now()) {
  return rooms
    .filter((room) => Number.isFinite(Date.parse(room.startsAt)) && Date.parse(room.startsAt) > now)
    .sort((left, right) => Date.parse(left.startsAt) - Date.parse(right.startsAt))[0] || null;
}

function seatsOf(room) {
  return { filled: room.participantCount, total: room.recruitmentCapacity + 1 };
}

function countdownLabel(startsAt, now = Date.now()) {
  const diffMs = Date.parse(startsAt) - now;
  if (!Number.isFinite(diffMs) || diffMs <= 0) return '';
  const minutes = Math.round(diffMs / 60000);
  if (minutes < 60) return ' · ' + Math.max(1, minutes) + '분 뒤';
  return ' · ' + Math.round(minutes / 60) + '시간 뒤';
}

/**
 * 홈의 여러 섹션이 함께 쓰는 모집 중 모임 현황이다.
 *
 * 오늘 열리는 모임과 앞으로 열리는 모임을 한 번에 받아 둔다. 오늘이 비면 곧 열리는 모임으로
 * 자리를 채우고, 둘 다 비면 모집 중인 모임이 아예 없는 상태로 본다. 빈 상태 문구와 CTA가
 * 서로 다른 사실을 말하지 않게 건수도 같은 조회에서 가져온다.
 */
function useOpenRooms(dataVersion) {
  const range = seoulTodayRange();
  const { data, loading, error, retry } = useRequest(async (signal) => {
    const [today, upcoming] = await Promise.all([
      api.getRooms({ status: 'RECRUITING', startsAtFrom: range.from, startsAtTo: range.to, page: 0, size: HOME_LIST_SIZE }, signal),
      api.getRooms({ status: 'RECRUITING', startsAtFrom: new Date().toISOString(), page: 0, size: HOME_LIST_SIZE }, signal)
    ]);
    return {
      today: (today.content || []).map(normalizeRoom),
      upcoming: (upcoming.content || []).map(normalizeRoom),
      recruitingCount: Number(upcoming.totalElements || 0)
    };
  }, [dataVersion]);

  const settled = Boolean(data) && !error;
  return {
    loading: loading && !data,
    error,
    retry,
    settled,
    rooms: data ? (data.today.length ? data.today : data.upcoming) : [],
    isToday: Boolean(data?.today.length),
    recruitingCount: data?.recruitingCount ?? 0,
    // 건수를 셀 수 있게 조회가 끝났고 모집 중인 모임이 하나도 없는 상태다.
    noOpenRooms: settled && data.recruitingCount === 0
  };
}

/**
 * 참가하는 모임이 없을 때의 히어로. 모집 중 건수에 따라 리드라인과 버튼이 함께 바뀐다.
 *
 * 비로그인 방문자도 홈 위쪽이 비지 않게 같은 카드를 쓴다. 참가 이력을 알 수 없으므로
 * 헤드라인만 바꾸고, 만들기·찾기 경로는 그대로 둔다. 로그인이 필요한 화면은 그 화면이 안내한다.
 */
function EmptyHero({ open, me }) {
  const lead = !open.settled
    ? '모집 중인 모임을 확인하고 있어요'
    : open.noOpenRooms ? '첫 모임을 열어보세요' : '지금 모집 중인 모임 ' + open.recruitingCount + '개';
  // 모집 중인 모임이 없으면 '모임 찾아보기'가 빈 목록으로 보내므로 '모임 만들기'를 주 버튼으로 올린다.
  const primary = open.noOpenRooms ? { label: '모임 만들기', href: '#/create' } : { label: '모임 찾아보기', href: '#/find' };
  const secondary = open.noOpenRooms ? { label: '게임 둘러보기', href: '#/game-list' } : { label: '모임 만들기', href: '#/create' };
  return (
    <section className="home-empty" aria-label={me ? '다음 내 모임' : '모임 시작하기'}>
      <p className="home-empty-lead">{lead}</p>
      {me
        ? <h2>{'아직 참가하는 '}<br />{'모임이 없어요'}</h2>
        : <h2>{'보드게임 같이 할 '}<br />{'사람을 찾아요'}</h2>}
      <p>마음에 드는 모임을 찾거나 직접 열어보세요.</p>
      <div className="btn-row">
        <a className="btn" href={primary.href}>{primary.label}</a>
        <a className="btn white" href={secondary.href}>{secondary.label}</a>
      </div>
    </section>
  );
}

function NextRoomCard({ dataVersion, open }) {
  const { data, loading, error, retry } = useRequest((signal) => Promise.all([
    api.getMyRooms({ role: 'joined', page: 0, size: 100 }, signal),
    api.getMyRooms({ role: 'hosted', page: 0, size: 100 }, signal)
  ]), [dataVersion]);
  const seen = new Set();
  const rooms = (data || [])
    .flatMap((page) => page.content || [])
    .map(normalizeRoom)
    .filter((room) => (seen.has(room.id) ? false : (seen.add(room.id), true)));
  const nextRoom = nextUpcomingRoom(
    rooms.filter((room) => room.status === 'RECRUITING' || room.status === 'CLOSED')
  );

  // 조회가 실패했을 때 빈 상태로 보여 주면 참가한 모임이 없는 것처럼 읽힌다.
  if (error) {
    return (
      <section aria-label="다음 내 모임" style={{ marginTop: 22 }}>
        <ErrorBox title="내 모임을 불러오지 못했어요" message={error} onRetry={retry} />
      </section>
    );
  }
  if (loading && !data) return <section className="home-next" aria-label="다음 내 모임"><p className="section-label">내 모임을 확인하고 있어요.</p></section>;
  if (!nextRoom) return <EmptyHero open={open} me />;

  return (
    <section className="home-next" aria-label="다음 내 모임" style={{ backgroundImage: 'url(' + (nextRoom.game?.imageUrl || defaultGameCover) + ')' }}>
      <div className="home-next-scrim" />
      <div className="home-next-body">
        <div className="home-next-lead">
          <span>다음 내 모임{countdownLabel(nextRoom.startsAt)}</span>
          <a className="section-link" href="#/my">내 모임 전체</a>
        </div>
        <a className="home-next-title" href={'#/session/' + nextRoom.id}>{nextRoom.title}</a>
        <p className="home-next-meta">{formatUpcomingStartsAt(nextRoom.startsAt)} · {nextRoom.place || nextRoom.region || '장소 미정'}</p>
        <div className="btn-row">
          <a className="btn" href={'#/session/' + nextRoom.id}>상세 보기</a>
          <a className="btn-square" href={'#/chat/' + nextRoom.id} aria-label="모임 채팅"><ChatIcon size={20} /></a>
        </div>
      </div>
    </section>
  );
}

/** 모집 중인 모임이 없을 때 목록 자리를 대신한다. 게임을 고르면 그 게임으로 바로 열 수 있다. */
function GameStarters({ dataVersion }) {
  const { data, loading, error, retry } = useRequest(
    (signal) => api.getGames({ page: 0, size: HOME_STARTER_SIZE }, signal),
    [dataVersion]
  );
  const games = (data?.content || []).map(normalizeGameSummary);
  return (
    <section aria-labelledby="home-starter-title">
      <div className="section-head"><h2 className="section-title" id="home-starter-title">어떤 게임으로 여시게요?</h2></div>
      <p className="screen-lead">지금 모집 중인 모임은 없어요. 게임을 고르면 그 게임으로 바로 열 수 있어요.</p>
      {error && <div style={{ marginTop: 18 }}><ErrorBox title="게임을 불러오지 못했어요" message={error} onRetry={retry} /></div>}
      {!error && loading && <div className="home-blank" aria-hidden="true"><p>게임을 불러오는 중이에요.</p></div>}
      {!!games.length && (
        <div className="home-starters nos">
          {games.map((game) => (
            <a className="home-starter" href={'#/game/' + game.id} key={game.id}>
              <span className="home-starter-tile"><Cover src={game.imageUrl} /></span>
              <span className="home-starter-name">{game.title}</span>
              <span className="home-starter-meta">{game.players}</span>
            </a>
          ))}
        </div>
      )}
    </section>
  );
}

function OpenRooms({ open, dataVersion }) {
  if (open.error) {
    return (
      <section aria-labelledby="home-today-title">
        <div className="section-head"><h2 className="section-title" id="home-today-title">오늘 열리는 모임</h2></div>
        <div style={{ marginTop: 18 }}><ErrorBox title="모임을 불러오지 못했어요" message={open.error} onRetry={open.retry} /></div>
      </section>
    );
  }
  if (open.loading) {
    return (
      <section aria-labelledby="home-today-title">
        <div className="section-head"><h2 className="section-title" id="home-today-title">오늘 열리는 모임</h2></div>
        <div style={{ marginTop: 18 }}><RoomSkeletons count={2} /></div>
      </section>
    );
  }
  // 모집 중인 모임이 아예 없으면 헤더와 '모두 보기'를 숨기고 게임 고르기로 자리를 채운다.
  if (open.noOpenRooms) return <GameStarters dataVersion={dataVersion} />;

  return (
    <section aria-labelledby="home-today-title">
      <div className="section-head">
        <h2 className="section-title" id="home-today-title">{open.isToday ? '오늘 열리는 모임' : '곧 열리는 모임'}</h2>
        <a className="section-link" href="#/find">모두 보기</a>
      </div>
      <div className="home-rooms">
        {open.rooms.map((room) => {
          const seats = seatsOf(room);
          return (
            <a className="roomrow" href={'#/session/' + room.id} key={room.id}>
              <Cover src={room.game?.imageUrl} style={{ height: 76, width: 76 }} />
              <span className="roomrow-body">
                <span className="roomrow-when">{formatUpcomingStartsAt(room.startsAt)} · {room.place || room.region || '장소 미정'}</span>
                <span className="roomrow-title">{room.title}</span>
                <span className="roomrow-seats">
                  <Meeples filled={seats.filled} total={seats.total} />
                  <SeatCount filled={seats.filled} total={seats.total} />
                </span>
              </span>
            </a>
          );
        })}
      </div>
    </section>
  );
}

/** P2 시안. 서버 연동이 없는 화면으로 들어가는 입구다. */
function MatchEntry() {
  return (
    <a className="home-entry" href="#/match">
      <span className="home-entry-mark"><MatchIcon /></span>
      <span className="home-entry-copy">
        <strong>실시간 온라인 매칭</strong>
        <span>사람이 모이면 보드게임아레나로 연결해요</span>
      </span>
      <span className="rowarrow"><ArrowIcon size={17} /></span>
    </a>
  );
}

function PopularGames({ dataVersion, noOpenRooms }) {
  const { data, loading, error, retry } = useRequest((signal) => api.getGameRankings(signal), [dataVersion]);
  const items = (data?.overall || []).slice(0, HOME_LIST_SIZE);
  // 랭킹 집계만 실패했거나 비어도 홈의 다른 섹션은 살린다.
  if (!loading && !error && !items.length) return null;
  return (
    <>
      <div className="divider" style={{ marginTop: 32 }} />
      <section aria-labelledby="home-rank-title">
        <div className="section-head">
          <h2 className="section-title" id="home-rank-title">인기 게임 랭킹</h2>
          {!error && <a className="section-link" href="#/game-rankings">랭킹 전체</a>}
        </div>
        {error && <div style={{ marginTop: 18 }}><ErrorBox title="랭킹을 불러오지 못했어요" message={error} onRetry={retry} /></div>}
        {!error && loading && <div style={{ marginTop: 18 }}><RankSkeletons /></div>}
        {!!items.length && (
          <div className="home-ranks">
            {items.map((item, index) => (
              <a className="home-rank" href={'#/game/' + item.gameId} key={item.gameId}>
                <span className="home-rank-no">{index + 1}</span>
                <Cover src={item.imageUrl} style={{ height: 46, width: 46 }} />
                <span className="home-rank-copy">
                  <strong>{item.name}</strong>
                  {/* 랭킹은 마감·종료 모임까지 세므로 모집 중이 0일 때 '열린 모임'이라고 쓰면 위 문구와 어긋난다. */}
                  <span>{noOpenRooms ? '전체 ' + item.roomCount + '개' : '열린 모임 ' + item.roomCount}</span>
                </span>
                <span className="rowarrow"><ArrowIcon size={16} /></span>
              </a>
            ))}
          </div>
        )}
      </section>
    </>
  );
}

export function MobileHomePanel({ me, dataVersion }) {
  const open = useOpenRooms(dataVersion);
  return (
    <>
      {me ? <NextRoomCard dataVersion={dataVersion} open={open} /> : <EmptyHero open={open} />}
      <OpenRooms open={open} dataVersion={dataVersion} />
      <MatchEntry />
      <PopularGames dataVersion={dataVersion} noOpenRooms={open.noOpenRooms} />
    </>
  );
}
