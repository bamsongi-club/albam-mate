import React from 'react';
import { api } from '../api';
import { normalizeRoom } from '../game';
import { useRequest } from '../shared/async';

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

export function nextUpcomingRoom(rooms, now = Date.now()) {
  return rooms
    .filter((room) => Number.isFinite(Date.parse(room.startsAt)) && Date.parse(room.startsAt) > now)
    .sort((left, right) => Date.parse(left.startsAt) - Date.parse(right.startsAt))[0] || null;
}

export function MobileHomePanel({ dataVersion }) {
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
  if (loading && !data) {
    return <section className="mobile-home-panel mobile-home-panel-loading" aria-live="polite">내 모임을 확인하고 있어요.</section>;
  }
  if (!nextRoom) {
    return (
      <section className="mobile-home-panel mobile-home-panel-empty" aria-label="다음 내 모임">
        <p className="mobile-home-eyebrow">다음 내 모임</p>
        <h2>예정된 모임이 없어요.</h2>
        <p>마음에 드는 모임을 찾거나 직접 열어보세요.</p>
        <div className="mobile-home-actions">
          <a className="mobile-home-secondary" href="#/find">모임 찾아보기</a>
          <a className="mobile-home-primary" href="#/create">모임 만들기</a>
        </div>
      </section>
    );
  }

  return (
    <section className="mobile-home-panel" aria-label="다음 내 모임">
      <p className="mobile-home-eyebrow">다음 내 모임</p>
      <h2>{nextRoom.title}</h2>
      <p className="mobile-home-meta">{formatUpcomingStartsAt(nextRoom.startsAt)} · {nextRoom.place || nextRoom.region || '장소 미정'}</p>
      <div className="mobile-home-actions">
        <a className="mobile-home-secondary" href={'#/chat/' + nextRoom.id}>채팅</a>
        <a className="mobile-home-primary" href={'#/session/' + nextRoom.id}>상세 보기</a>
      </div>
    </section>
  );
}
