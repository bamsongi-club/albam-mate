import React, { useState } from 'react';
import { api } from '../api';
import { ErrorBox, LoadingBox, SectionIcon } from '../shared/ui';
import { useRequest } from '../shared/async';

// 두 랭킹은 한 응답으로 함께 오므로 탭을 바꿀 때 다시 조회하지 않는다.
const RANKING_TABS = [
  { key: 'overall', label: '전체', emptyMessage: '아직 집계할 모임이 없어요. 첫 모임을 열어보세요.' },
  { key: 'upcomingWeek', label: '앞으로 7일', emptyMessage: '앞으로 7일 안에 시작하는 모임이 없어요.' }
];

// 출시 연도는 없을 수 있어 영문명 뒤에 있을 때만 괄호로 덧붙인다.
function originLabel(item) {
  return item.releaseYear ? item.englishName + ' (' + item.releaseYear + ')' : item.englishName;
}

function GameRankingItem({ item }) {
  return (
    <a className="ranking-row" href={'#/game/' + item.gameId}>
      <span className="ranking-rank">{item.rank}</span>
      <span className="ranking-art">{item.imageUrl ? <img src={item.imageUrl} alt="" loading="lazy" /> : '🎲'}</span>
      <span className="ranking-game">
        <span className="ranking-name">{item.name}</span>
        <span className="ranking-origin">{originLabel(item)}</span>
        <span className="ranking-desc">{item.description}</span>
      </span>
      {/* 표 머리글을 낭독에서 뺀 대신 숫자의 뜻을 항목 안에 남긴다. */}
      <span className="ranking-count"><span className="sr-only">모임 </span><b>{item.roomCount}</b>개</span>
    </a>
  );
}

export function GameRankingView({ dataVersion }) {
  const [tabKey, setTabKey] = useState(RANKING_TABS[0].key);
  // 조회에 실패한 뒤 같은 요청을 다시 보내는 신호다.
  const [retryVersion, setRetryVersion] = useState(0);
  const { data, loading, error } = useRequest(
    (signal) => api.getGameRankings(signal),
    [dataVersion, retryVersion]
  );
  const tab = RANKING_TABS.find((candidate) => candidate.key === tabKey) || RANKING_TABS[0];
  const items = data?.[tab.key] || [];

  return (
    <>
      <h2><SectionIcon name="games" />인기 게임 랭킹</h2>
      <p className="hint search-header-hint">밤송이에서 그 게임으로 열린 모임 수로 매긴 순위예요.</p>
      <div className="tabs-row">
        <div className="tabs">
          {RANKING_TABS.map((candidate) => (
            <button
              key={candidate.key}
              type="button"
              className={candidate.key === tab.key ? 'on' : ''}
              aria-pressed={candidate.key === tab.key}
              onClick={() => setTabKey(candidate.key)}
            >
              {candidate.label}
            </button>
          ))}
        </div>
      </div>
      {error && (
        <div className="infobox red" role="alert">
          {error}
          <button className="infobox-action" type="button" onClick={() => setRetryVersion((version) => version + 1)}>다시 시도</button>
        </div>
      )}
      {!error && loading && !data && <LoadingBox />}
      {!error && !!items.length && (
        <div className="ranking-table">
          <div className="ranking-head" aria-hidden="true">
            <span className="ranking-rank">순위</span>
            <span className="ranking-game">게임</span>
            <span className="ranking-count">모임 수</span>
          </div>
          {items.map((item) => <GameRankingItem key={item.gameId} item={item} />)}
        </div>
      )}
      {!error && !loading && !items.length && <div className="infobox">{tab.emptyMessage}</div>}
    </>
  );
}
