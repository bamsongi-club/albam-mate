import React, { useState } from 'react';
import { api } from '../api';
import { Cover, ErrorBox, RankSkeletons, ScreenTitle, TopBar } from '../shared/ui';
import { useRequest } from '../shared/async';

// 두 랭킹은 한 응답으로 함께 오므로 탭을 바꿀 때 다시 조회하지 않는다.
const RANKING_TABS = [
  { key: 'overall', label: '전체', emptyMessage: '아직 집계할 모임이 없어요. 첫 모임을 열어보세요.' },
  { key: 'pastWeek', label: '지난 7일', emptyMessage: '지난 7일 동안 시작한 모임이 없어요.' }
];

// 출시 연도는 없을 수 있어 영문명 뒤에 있을 때만 괄호로 덧붙인다.
function originLabel(item) {
  return item.releaseYear ? item.englishName + ' (' + item.releaseYear + ')' : item.englishName;
}

export function GameRankingView({ onBack, dataVersion }) {
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
    <div className="screen sub">
      <TopBar onBack={onBack} />
      <div className="screen-body pad-bottom">
        <ScreenTitle>인기 게임</ScreenTitle>
        <p className="screen-lead">밤송이에서 그 게임으로 열린 모임 수로 매긴 순위예요.</p>
        <div className="tabline">
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

        {error && (
          <div style={{ marginTop: 24 }}>
            <ErrorBox
              title="랭킹을 불러오지 못했어요"
              message={error}
              onRetry={() => setRetryVersion((version) => version + 1)}
            />
          </div>
        )}
        {!error && loading && !data && <div style={{ marginTop: 22 }}><RankSkeletons /></div>}
        {!error && !!items.length && (
          <div className="ranklist">
            {items.map((item) => (
              <a className="rank-row" href={'#/game/' + item.gameId} key={item.gameId}>
                <span className="rank-no">{item.rank}</span>
                <span className="rank-tile"><Cover src={item.imageUrl} /></span>
                <span className="rank-copy">
                  <strong>{item.name}</strong>
                  <span>{originLabel(item)}</span>
                </span>
                <span className="rank-count"><span className="sr-only">모임 </span>{item.roomCount}개</span>
              </a>
            ))}
          </div>
        )}
        {!error && !loading && !items.length && <p className="screen-lead" style={{ marginTop: 24 }}>{tab.emptyMessage}</p>}
      </div>
    </div>
  );
}
