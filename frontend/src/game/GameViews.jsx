import React, { useEffect, useState } from 'react';
import { api } from '../api';
import { ErrorBox, LoadingBox, Pagination, SearchHeader, SectionIcon } from '../shared/ui';
import { usePaginatedRequest, useRequest } from '../shared/async';
import { GAME_LIST_PAGE_SIZE, ROOM_LIST_PAGE_SIZE, EMPTY_GAME_FILTERS } from './constants';
import { gameFilterParameters } from './filterLogic';
import { gameMeta, normalizeGameSummary, normalizeRoom } from './data';
import { GameFilters } from './GameFilters';
import { useAppliedGameFilters, usePlayedGames } from './hooks';

function hasStarted(room) {
  return Date.now() >= Date.parse(room.startsAt);
}

function LoginRequiredView({ message = '이 기능은 로그인 후 이용할 수 있어요.' }) {
  return <div className="card"><h2>로그인이 필요해요</h2><p className="hint" style={{ marginBottom: 16 }}>{message}</p><a className="btn" href="#/auth">로그인 또는 회원가입</a></div>;
}

/**
 * 본인이 해 본 게임으로 표시했는지를 켜고 끄는 조작이다.
 *
 * 관계가 없거나 아직 판정하지 않은 상태를 `해보지 않음`으로 부르지 않고 눌리지 않은 상태로만 둔다.
 * 다른 사용자의 관계는 응답에 없으므로 화면에도 없다.
 */
function PlayedGameToggle({ played, pending, onToggle, compact = false }) {
  return (
    <button
      type="button"
      className={'played-toggle' + (compact ? ' dot' : '') + (played ? ' on' : '')}
      // 점만 두는 목록 카드에서도 조작 이름은 화면 낭독과 hover 안내로 남긴다.
      aria-label={compact ? '해봤어요' : undefined}
      title={compact ? '해봤어요' : undefined}
      aria-pressed={played === true}
      disabled={pending}
      onClick={onToggle}
    >
      {compact
        ? <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" strokeWidth="3.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><polyline points="20 6 9 17 4 12" /></svg>
        : '해봤어요'}
    </button>
  );
}

function GameCard({ game, played, pending, onTogglePlayed }) {
  return (
    <div className="gcard-shell">
      <a className="gcard" href={'#/game/' + game.id}>
        <div className="gart">{game.imageUrl ? <img src={game.imageUrl} alt="" loading="lazy" /> : '🎲'}</div>
        <div className="gtitle">
          <span className="gname">{game.title}</span>
          {game.englishName && <span className="gen">{game.englishName}</span>}
        </div>
        {/* 카드 높이를 맞추려고 한 줄로 자른다. 잘린 뒷부분은 hover로 확인한다. */}
        <div className="gmeta" title={gameMeta(game)}>{gameMeta(game)}</div>
        <div className={'gsess' + (game.upcomingRoomCount ? '' : ' none')}>예정 모임 {game.upcomingRoomCount}개</div>
      </a>
      {/* 카드 전체가 상세 링크라 해 본 게임 조작은 링크 밖에 두고 표지 모서리에 점으로 얹는다. */}
      <PlayedGameToggle played={played} pending={pending} onToggle={onTogglePlayed} compact />
    </div>
  );
}

export function GamesView({ title, gameQuery, onGameQueryChange, dataVersion, onPlayedError, initialFilters = EMPTY_GAME_FILTERS }) {
  const [input, setInput] = useState(gameQuery);
  const [filters, setFilters] = useState(initialFilters);
  const keyword = gameQuery.trim();
  const parameters = gameFilterParameters(useAppliedGameFilters(filters));
  const filterKey = JSON.stringify(parameters);
  const playedGames = usePlayedGames(onPlayedError);
  // 해 본 게임 필터가 활성화된 동안에만 표시·취소 성공을 재조회 신호로 쓴다.
  // 그 외에는 조회 결과가 playedByMe로 걸러지지 않으므로 다시 부를 필요가 없다.
  const playedRefreshKey = filters.playedFilter ? playedGames.version : 0;
  const { data, loading, error, unauthenticated, setPage } = usePaginatedRequest(
    (page, signal) => api.getGames({ keyword, ...parameters, page, size: GAME_LIST_PAGE_SIZE }, signal),
    [keyword, filterKey, dataVersion, playedRefreshKey]
  );
  const games = (data?.content || []).map(normalizeGameSummary);
  useEffect(() => setInput(gameQuery), [gameQuery]);
  return (
    <>
      <SearchHeader
        icon="games"
        title={title}
        keywordId="game-q"
        keywordLabel="게임 이름 검색"
        inputValue={input}
        onInputChange={(event) => setInput(event.target.value)}
        onSubmit={(event) => { event.preventDefault(); onGameQueryChange(input.trim()); }}
        placeholder="게임 이름으로 검색"
        filtersSlot={(searchSlot) => <GameFilters searchSlot={searchSlot} filters={filters} onChange={setFilters} />}
      />
      {error && (unauthenticated
        ? <LoginRequiredView message="해 본 게임으로 거르려면 로그인해주세요." />
        : <ErrorBox message={error} />)}
      {!error && loading && !data && <LoadingBox />}
      {!error && !!games.length && (
        <div className="grid cols3">
          {games.map((game) => (
            <GameCard
              key={game.id}
              game={game}
              played={playedGames.stateOf(game)}
              pending={playedGames.isPending(game)}
              onTogglePlayed={() => playedGames.toggle(game)}
            />
          ))}
        </div>
      )}
      {!error && !loading && !games.length && <div className="infobox" style={{ marginTop: 14 }}>검색 결과가 없어요. 다른 게임 이름으로 다시 찾아보세요.</div>}
      {!error && !!games.length && <Pagination page={data?.page ?? 0} totalPages={data?.totalPages ?? 0} loading={loading} onChange={setPage} />}
    </>
  );
}

export function GameDetailView({ gameId, onCreateGame, dataVersion, onPlayedError, renderRoom }) {
  const playedGames = usePlayedGames(onPlayedError);
  const { data: gameData, loading: gameLoading, error: gameError } = useRequest(
    (signal) => api.getGame(gameId, signal),
    [gameId, dataVersion]
  );
  const { data: roomPage, loading: roomsLoading, error: roomsError, setPage: setRoomPage } = usePaginatedRequest(
    (page, signal) => api.getRooms({ type: 'GAME_FOCUSED', gameId, page, size: ROOM_LIST_PAGE_SIZE }, signal),
    [gameId, dataVersion]
  );
  if (gameError || roomsError) return <ErrorBox message={gameError || roomsError} />;
  if ((gameLoading || roomsLoading) && (!gameData || !roomPage)) return <LoadingBox />;
  const game = gameData ? normalizeGameSummary(gameData) : null;
  if (!game) return <div className="card">게임을 찾을 수 없어요.</div>;
  const rooms = (roomPage?.content || []).map(normalizeRoom);
  const upcomingRooms = rooms.filter((room) => !hasStarted(room));
  return (
    <>
      <div className="card">
        <div className="detail-head">
          <div className="dart">{game.imageUrl ? <img src={game.imageUrl} alt="" /> : '🎲'}</div>
          <div>
            <h2>{game.title}</h2>
            <div className="gen">{game.englishName}</div>
            <div className="gmeta" style={{ fontSize: 14 }}>{gameMeta(game)}</div>
            {game.tag && <span className="chip">{game.tag}</span>}
            {game.description && <p className="hint" style={{ marginTop: 12 }}>{game.description}</p>}
            <div className="page-actions" style={{ marginTop: 15 }}>
              <button className="btn" type="button" onClick={() => onCreateGame(game)}>이 게임으로 모임 만들기</button>
              <PlayedGameToggle
                played={playedGames.stateOf(game)}
                pending={playedGames.isPending(game)}
                onToggle={() => playedGames.toggle(game)}
              />
            </div>
          </div>
        </div>
      </div>
      <section style={{ marginTop: 32 }}>
        <h2><SectionIcon name="calendar" />예정 모임 <span className="cnt">{roomPage?.totalElements ?? upcomingRooms.length}개</span></h2>
        {upcomingRooms.length ? <div className="grid cols2">{upcomingRooms.map((room) => renderRoom?.(room))}</div> : <div className="infobox">아직 공개 예정 모임이 없어요. 첫 모임을 만들어보세요.</div>}
        <Pagination page={roomPage?.page ?? 0} totalPages={roomPage?.totalPages ?? 0} loading={roomsLoading} onChange={setRoomPage} />
      </section>
    </>
  );
}
