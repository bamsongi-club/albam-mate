import React, { useEffect, useState } from 'react';
import { api } from '../api';
import poweredByBgg from '../../assets/powered-by-bgg.svg';
import { BggAttribution, CheckIcon, Cover, ErrorBox, PlusIcon, Pagination, RoomSkeletons, ScreenTitle, SearchIcon, StateBlock, TopBar } from '../shared/ui';
import { usePaginatedRequest, useRequest } from '../shared/async';
import { GAME_LIST_PAGE_SIZE, ROOM_LIST_PAGE_SIZE, EMPTY_GAME_FILTERS, PLAYED_FILTER_OPTIONS, DEFAULT_GAME_COVER_URL } from './constants';
import { gameFilterParameters } from './filterLogic';
import { gameMeta, normalizeGameSummary, normalizeRoom } from './data';
import { GameFilters } from './GameFilters';
import { useAppliedGameFilters, usePlayedGames } from './hooks';

function hasStarted(room) {
  return Date.now() >= Date.parse(room.startsAt);
}

function metadataLabel(metadata) {
  if (typeof metadata === 'string') return metadata;
  return metadata?.nameKo || metadata?.name || metadata?.nameEn || '';
}

function LoginRequiredView({ message = '이 기능은 로그인 후 이용할 수 있어요.' }) {
  return (
    <StateBlock title="로그인이 필요해요" description={message}>
      <a className="btn" href="#/auth">로그인 또는 회원가입</a>
    </StateBlock>
  );
}

/**
 * 본인이 해 본 게임으로 표시했는지를 켜고 끄는 조작이다.
 *
 * 관계가 없거나 아직 판정하지 않은 상태를 `해보지 않음`으로 부르지 않고 눌리지 않은 상태로만 둔다.
 * 다른 사용자의 관계는 응답에 없으므로 화면에도 없다.
 */
function PlayedGameBadge({ played, pending, onToggle }) {
  const label = pending ? '저장 중…' : played ? '해봤어요 ✓' : '해봤어요';
  return (
    <button
      type="button"
      className={'played-badge' + (played ? ' on' : '')}
      aria-label={label}
      title={label}
      aria-pressed={played === true}
      disabled={pending}
      onClick={(event) => { event.preventDefault(); onToggle(); }}
    >
      <CheckIcon />
    </button>
  );
}

function GameCard({ game, played, pending, onTogglePlayed }) {
  const coverSrc = game.imageUrl || DEFAULT_GAME_COVER_URL;
  return (
    <div className="gamecard">
      <a href={'#/game/' + game.id} aria-label={game.title + ' 상세'}>
        <span className="cover-tile">
          <Cover src={coverSrc} />
        </span>
        <span className="gamecard-name">{game.title}</span>
        <span className="gamecard-meta">{gameMeta(game)}</span>
      </a>
      {/* 표지 위 체크 배지는 상세 링크 밖에 둔다. */}
      <PlayedGameBadge played={played} pending={pending} onToggle={onTogglePlayed} />
      {game.upcomingRoomCount > 0 && <span className="pill-green">열린 모임 {game.upcomingRoomCount}</span>}
    </div>
  );
}

function GameSlicePagination({ page, hasNext, loading, onChange }) {
  if (page <= 0 && !hasNext) return null;
  return (
    <nav className="pagination" aria-label="페이지 이동">
      <button className="page-btn" type="button" disabled={loading || page <= 0} onClick={() => onChange(page - 1)} aria-label="이전 페이지">이전</button>
      <span className="page-btn on" aria-current="page">{page + 1}페이지</span>
      <button className="page-btn" type="button" disabled={loading || !hasNext} onClick={() => onChange(page + 1)} aria-label="다음 페이지">다음</button>
    </nav>
  );
}

export function GamesView({ title, gameQuery, onGameQueryChange, dataVersion, onPlayedError, headerActions, initialFilters = EMPTY_GAME_FILTERS, onBack }) {
  const [input, setInput] = useState(gameQuery);
  const [filters, setFilters] = useState(initialFilters);
  const query = gameQuery.trim();
  const parameters = gameFilterParameters(useAppliedGameFilters(filters));
  const filterKey = JSON.stringify(parameters);
  const playedGames = usePlayedGames(onPlayedError);
  // 해 본 게임 필터가 활성화된 동안에만 표시·취소 성공을 재조회 신호로 쓴다.
  // 그 외에는 조회 결과가 playedByMe로 걸러지지 않으므로 다시 부를 필요가 없다.
  const playedRefreshKey = filters.playedFilter ? playedGames.version : 0;
  const { data, loading, error, unauthenticated, setPage, retry } = usePaginatedRequest(
    (page, signal) => {
      // 검색어가 없으면 기존 인기순 목록(GAME-01)을 그대로 보여주고, 검색어가 있으면 의미 검색으로 넘긴다.
      // 탭 없이 한 검색창에서 이름·문장 검색을 모두 받기 위한 분기다.
      if (!query) return api.getGames({ ...parameters, page, size: GAME_LIST_PAGE_SIZE }, signal);
      return api.getGameSearch({ query, ...parameters, page, size: GAME_LIST_PAGE_SIZE }, signal);
    },
    [query, filterKey, dataVersion, playedRefreshKey]
  );
  const games = (data?.content || []).map(normalizeGameSummary);
  const isSearching = Boolean(query);
  useEffect(() => setInput(gameQuery), [gameQuery]);

  const playedChips = (
    <>
      {PLAYED_FILTER_OPTIONS.map((option) => (
        <button
          type="button"
          key={option.value || 'all'}
          className={'chip' + (filters.playedFilter === option.value ? ' on' : '')}
          aria-pressed={filters.playedFilter === option.value}
          onClick={() => setFilters((current) => ({ ...current, playedFilter: option.value }))}
        >
          {option.label}
        </button>
      ))}
    </>
  );

  return (
    <>
      {onBack
        ? <TopBar onBack={onBack} title={title} action={headerActions} />
        : null}
      <div className={'screen-body pad-bottom' + (onBack ? '' : ' pad-top')}>
      {!onBack && <ScreenTitle actions={headerActions}>{title}</ScreenTitle>}
      <form
        className="searchbox"
        style={{ marginTop: 16 }}
        onSubmit={(event) => { event.preventDefault(); onGameQueryChange(input.trim()); }}
      >
        <SearchIcon />
        <label className="sr-only" htmlFor="game-q">게임 이름이나 찾는 느낌으로 검색</label>
        <input
          id="game-q"
          value={input}
          onChange={(event) => setInput(event.target.value)}
          placeholder="게임 이름 또는 예: 가족과 짧게 즐길 협동 게임"
          maxLength={200}
        />
      </form>
      <GameFilters
        filters={filters}
        onChange={setFilters}
        quickSlot={playedChips}
        resultCount={Number.isFinite(data?.totalElements) ? data.totalElements : undefined}
      />
      {!error && <p className="section-label" style={{ marginTop: 18 }}>{loading && !data ? '불러오는 중' : '게임 목록'}</p>}
      {error && (
        <div style={{ marginTop: 26 }}>
          {unauthenticated
            ? <LoginRequiredView message="해 본 게임으로 거르려면 로그인해주세요." />
            : <ErrorBox message={error} title="게임을 불러오지 못했어요" onRetry={retry} />}
        </div>
      )}
      {!error && loading && !data && <div style={{ marginTop: 22 }}><RoomSkeletons count={3} /></div>}
      {!error && !!games.length && (
        <div className="gamegrid" style={{ marginTop: 18 }}>
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
      {!error && !loading && !games.length && (
        <div style={{ marginTop: 26 }}>
          <StateBlock title="검색 결과가 없어요" description={isSearching ? '다른 표현이나 조건으로 다시 시도해보세요.' : '게임 이름의 일부만 넣어보세요.'} />
        </div>
      )}
      {!error && data && (
        Number.isFinite(data.totalPages)
          ? <Pagination page={data.page ?? 0} totalPages={data.totalPages} loading={loading} onChange={setPage} className="tab-fab-clear" />
          : <GameSlicePagination page={data.page ?? 0} hasNext={Boolean(data.hasNext)} loading={loading} onChange={setPage} />
      )}
      </div>
    </>
  );
}

function ComplexityPips({ complexity }) {
  const score = Number(complexity);
  const filled = Number.isFinite(score) ? Math.round(score) : 0;
  return (
    <p className="game-pips">
      {Array.from({ length: 5 }, (_, index) => <i className={index < filled ? 'on' : ''} key={index} aria-hidden="true" />)}
      <span>난이도 {complexity}</span>
    </p>
  );
}

export function GameDetailView({ gameId, onCreateGame, onBack, dataVersion, onPlayedError, renderRoom }) {
  const playedGames = usePlayedGames(onPlayedError);
  const { data: gameData, loading: gameLoading, error: gameError, retry: retryGame } = useRequest(
    (signal) => api.getGame(gameId, signal),
    [gameId, dataVersion]
  );
  const { data: roomPage, loading: roomsLoading, error: roomsError, setPage: setRoomPage, retry: retryRooms } = usePaginatedRequest(
    (page, signal) => api.getRooms({ type: 'GAME_FOCUSED', gameId, page, size: ROOM_LIST_PAGE_SIZE }, signal),
    [gameId, dataVersion]
  );
  const game = gameData ? normalizeGameSummary(gameData) : null;
  const played = game ? playedGames.stateOf(game) : false;
  const [detailOpen, setDetailOpen] = useState(false);
  // 다른 게임으로 이동하면 앞 게임에서 펼쳐 둔 상태가 남지 않도록 되돌린다.
  useEffect(() => setDetailOpen(false), [gameId]);

  if (gameError || roomsError) {
    return (
      <div className="screen sub">
        <TopBar onBack={onBack} />
        <div className="screen-body pad-bottom"><ErrorBox message={gameError || roomsError} title="게임을 불러오지 못했어요" onRetry={() => { retryGame(); retryRooms(); }} /></div>
      </div>
    );
  }
  if ((gameLoading || roomsLoading) && (!gameData || !roomPage)) {
    return (
      <div className="screen sub">
        <TopBar onBack={onBack} />
        <div className="screen-body pad-bottom"><RoomSkeletons count={2} /></div>
      </div>
    );
  }
  if (!game) {
    return (
      <div className="screen sub">
        <TopBar onBack={onBack} />
        <div className="screen-body pad-bottom"><StateBlock title="게임을 찾을 수 없어요" description="주소를 다시 확인해주세요." /></div>
      </div>
    );
  }

  const categories = game.categories.map(metadataLabel).filter(Boolean);
  const themes = game.themes.map(metadataLabel).filter(Boolean);
  const mechanisms = game.mechanisms.map(metadataLabel).filter(Boolean);
  const specs = [
    { label: '인원', value: game.players },
    { label: '플레이 시간', value: game.time },
    { label: '권장 연령', value: game.minAge ? game.minAge + '세+' : '' }
  ].filter((spec) => spec.value);
  const tags = [...categories, ...themes, ...mechanisms];
  // 상세 설명은 빈 줄로 문단을 나눠 저장한다. 문단마다 따로 렌더링해야 줄바꿈이 살아난다.
  const detailParagraphs = game.detailDescription
    .split(/\n\s*\n/)
    .map((paragraph) => paragraph.trim())
    .filter(Boolean);
  const rooms = (roomPage?.content || []).map(normalizeRoom).filter((room) => !hasStarted(room));

  return (
    <div className="screen sub">
      <TopBar onBack={onBack} backLabel="게임 목록으로" />
      <div className="screen-body pad-bottom">
        <div className="game-head">
          <div className="game-head-tile"><Cover src={game.imageUrl} /></div>
          <div className="game-head-copy">
            <h1>{game.title}</h1>
            {/* 값이 없는 항목은 자리를 비운다. 추정하거나 대체값을 넣지 않는다. */}
            {(game.englishName || game.releaseYear) && (
              <p className="game-head-en">{[game.englishName, game.releaseYear].filter(Boolean).join(' · ')}</p>
            )}
            {!!categories.length && <p className="game-head-cat">{categories.join(' · ')}</p>}
            {game.complexity && <ComplexityPips complexity={game.complexity} />}
          </div>
        </div>

        {!!specs.length && (
          <dl className="game-specs">
            {specs.map((spec) => <div key={spec.label}><dt>{spec.label}</dt><dd>{spec.value}</dd></div>)}
          </dl>
        )}

        {!!tags.length && (
          <div className="taglist" aria-label="게임 카테고리와 테마, 메커니즘">
            {tags.map((tag) => <span className="tag" key={tag}>{tag}</span>)}
          </div>
        )}

        <button
          type="button"
          className={'btn' + (played ? '' : ' fill')}
          style={{ marginTop: 24 }}
          aria-pressed={played === true}
          disabled={playedGames.isPending(game)}
          onClick={() => playedGames.toggle(game)}
        >
          <CheckIcon size={17} width={2.6} />
          {playedGames.isPending(game) ? '저장 중…' : '해봤어요'}
        </button>

        {(game.description || detailParagraphs.length > 0) && (
          <>
            <div className="divider" style={{ margin: '26px 0' }} />
            {game.description && <p className="longtext">{game.description}</p>}
            {detailParagraphs.length > 0 && (
              <>
                <button
                  type="button"
                  className="btn fill"
                  style={{ marginTop: 18 }}
                  aria-expanded={detailOpen}
                  aria-controls="game-detail-description"
                  onClick={() => setDetailOpen((open) => !open)}
                >
                  {detailOpen ? '상세 설명 접기' : '상세 설명 보기'}
                </button>
                <div id="game-detail-description" hidden={!detailOpen} style={{ marginTop: 18 }}>
                  {detailParagraphs.map((paragraph, index) => (
                    <p key={paragraph} className="longtext" style={{ marginTop: index === 0 ? 0 : 18 }}>
                      {paragraph}
                    </p>
                  ))}
                </div>
              </>
            )}
          </>
        )}

        <h2 className="section-title" style={{ marginTop: 30 }}>이 게임으로 열린 모임 {roomPage?.totalElements ?? rooms.length}</h2>
        <div className="roomlist" style={{ marginTop: 18 }}>
          {rooms.length
            ? rooms.map((room) => renderRoom?.(room))
            : <p className="screen-lead">아직 이 게임으로 열린 모임이 없어요. 첫 모임을 열어보세요.</p>}
        </div>
        <Pagination page={roomPage?.page ?? 0} totalPages={roomPage?.totalPages ?? 0} loading={roomsLoading} onChange={setRoomPage} />

        <BggAttribution logoSrc={poweredByBgg} />
      </div>
      <div className="stickybar">
        <button className="btn cta" type="button" onClick={() => onCreateGame(game)}>
          <PlusIcon size={17} />이 게임으로 모임 만들기
        </button>
      </div>
    </div>
  );
}
