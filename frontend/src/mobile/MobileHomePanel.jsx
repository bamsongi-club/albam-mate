import React from 'react';
import { api } from '../api';
import { normalizeRoom } from '../game';
import { useRequest } from '../shared/async';
import { ArrowIcon, ChatIcon, Cover, Meeples, RoomSkeletons, SeatCount } from '../shared/ui';

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

function NextRoomCard({ dataVersion }) {
  const { data, loading, error } = useRequest((signal) => Promise.all([
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

  if (error) return null;
  if (loading && !data) return <section className="home-next" aria-label="다음 내 모임"><p className="section-label">내 모임을 확인하고 있어요.</p></section>;
  if (!nextRoom) {
    return (
      <section className="home-empty" aria-label="다음 내 모임">
        <h2>{'예정된 모임이 '}<br />{'없어요'}</h2>
        <p>마음에 드는 모임을 찾거나 직접 열어보세요.</p>
        <div className="btn-row">
          <a className="btn" href="#/find">모임 찾아보기</a>
          <a className="btn fill" href="#/create">모임 만들기</a>
        </div>
      </section>
    );
  }

  const seats = seatsOf(nextRoom);
  return (
    <section className="home-next" aria-label="다음 내 모임">
      <div className="home-next-lead">
        <span>다음 내 모임</span>
        <a className="section-link" href="#/my">내 모임 전체</a>
      </div>
      <a className="home-next-title" href={'#/session/' + nextRoom.id}>{nextRoom.title}</a>
      <p className="home-next-meta">{formatUpcomingStartsAt(nextRoom.startsAt)} · {nextRoom.place || nextRoom.region || '장소 미정'}</p>
      <div className="home-next-seats">
        <Meeples filled={seats.filled} total={seats.total} size="md" />
        <SeatCount filled={seats.filled} total={seats.total} size="lg" />
      </div>
      <div className="btn-row">
        <a className="btn" href={'#/session/' + nextRoom.id}>상세 보기</a>
        <a className="btn-square" href={'#/chat/' + nextRoom.id} aria-label="모임 채팅"><ChatIcon size={20} /></a>
      </div>
    </section>
  );
}

function TodayRooms({ dataVersion }) {
  const range = seoulTodayRange();
  const { data, loading } = useRequest(
    (signal) => api.getRooms({ status: 'RECRUITING', startsAtFrom: range.from, startsAtTo: range.to, page: 0, size: 3 }, signal),
    [dataVersion]
  );
  const rooms = (data?.content || []).map(normalizeRoom);
  return (
    <section aria-labelledby="home-today-title">
      <div className="section-head">
        <h2 className="section-title" id="home-today-title">오늘 열리는 모임</h2>
        <a className="section-link" href="#/find">모두 보기</a>
      </div>
      {loading && !data && <div style={{ marginTop: 18 }}><RoomSkeletons count={2} /></div>}
      {!loading && !rooms.length && <p className="screen-lead">오늘 시작하는 모임이 아직 없어요.</p>}
      {!!rooms.length && (
        <div className="home-rooms">
          {rooms.map((room) => {
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
      )}
    </section>
  );
}

function PopularGames({ dataVersion }) {
  const { data, loading } = useRequest((signal) => api.getGameRankings(signal), [dataVersion]);
  const items = (data?.overall || []).slice(0, 3);
  if (!loading && !items.length) return null;
  return (
    <section aria-labelledby="home-rank-title">
      <div className="section-head">
        <h2 className="section-title" id="home-rank-title">인기 게임 랭킹</h2>
        <a className="section-link" href="#/game-rankings">랭킹 전체</a>
      </div>
      <div className="home-ranks">
        {items.map((item, index) => (
          <a className="home-rank" href={'#/game/' + item.gameId} key={item.gameId}>
            <span className="home-rank-no">{index + 1}</span>
            <Cover src={item.imageUrl} style={{ height: 46, width: 46 }} />
            <span className="home-rank-copy">
              <strong>{item.name}</strong>
              <span>열린 모임 {item.roomCount}</span>
            </span>
            <span className="rowarrow"><ArrowIcon size={16} /></span>
          </a>
        ))}
      </div>
    </section>
  );
}

export function MobileHomePanel({ me, dataVersion }) {
  return (
    <>
      {me && <NextRoomCard dataVersion={dataVersion} />}
      <TodayRooms dataVersion={dataVersion} />
      <div className="divider" style={{ marginTop: 32 }} />
      <PopularGames dataVersion={dataVersion} />
    </>
  );
}
