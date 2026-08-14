import React, { useState } from 'react';
import { api } from '../api';
import { normalizeGameSummary } from '../game';
import { useRequest } from '../shared/async';
import { playerColor, playerTextColor } from '../shared/players';
import { ArrowIcon, Cover, ErrorBox, SendIcon, StateBlock, TopBar } from '../shared/ui';

/**
 * P2 시안. 담당자 명세와 매칭 서버 API가 아직 없다.
 *
 * 화면 배치를 확인할 수 있게 레이아웃은 모두 그리되, 서버가 해야 할 일은 실행하지 않고
 * 준비 중임을 알린다. 화면 이동만 실제로 동작한다. 매칭 방은 오프라인 ROOM과 별개이며
 * 내 모임에 쌓이지 않고 RoomStatus와도 섞지 않는다.
 */

export const P2_NOTICE = '아직 준비 중인 기능이에요.';

export const MATCH_PHASES = ['idle', 'searching', 'matched', 'failed'];

const MATCH_CAPACITIES = [2, 3, 4];
const CANDIDATE_SIZE = 4;
const MEMBER_NAMES = ['나', '지현', '현수', '민경'];

const PREVIEW_MESSAGES = [
  { system: true, text: '온라인 방이 열렸어요. 무엇을 할지 정해보세요.' },
  { name: '지현', text: '가벼운 걸로 한 판 하시죠' },
  { me: true, text: '좋아요, 저는 아무거나 괜찮아요' }
];

function useCandidates(dataVersion) {
  const { data, loading, error, retry } = useRequest(
    (signal) => api.getGames({ page: 0, size: CANDIDATE_SIZE }, signal),
    [dataVersion]
  );
  return { games: (data?.content || []).map(normalizeGameSummary), loading, error, retry };
}

function membersOf(capacity) {
  return Array.from({ length: capacity }, (_, index) => {
    const name = MEMBER_NAMES[index % MEMBER_NAMES.length];
    const color = playerColor(index);
    return { name, initial: [...name][0], color, textColor: playerTextColor(color) };
  });
}

/** 내 표 1과 나머지 인원의 표를 나눠 표 합계를 항상 참가자 수와 같게 유지한다. */
function tally(candidates, capacity, myVote) {
  const others = Math.max(0, capacity - 1);
  const counts = candidates.map(() => 0);
  for (let index = 0; index < others; index += 1) counts[index % candidates.length] += 1;
  return candidates.map((game, index) => ({
    ...game,
    votes: counts[index] + (myVote === game.id ? 1 : 0),
    mine: myVote === game.id
  }));
}

function Members({ members, filled, pulsing }) {
  return (
    <div className={'match-seats' + (pulsing ? ' searching' : '')} aria-hidden="true">
      {members.map((member, index) => (
        <span className="match-seat" key={index} style={index < filled ? { color: member.color } : undefined}>
          <i /><b />
        </span>
      ))}
    </div>
  );
}

function Avatars({ members, small }) {
  return (
    <div className="match-avatars">
      {members.map((member, index) => (
        <span className={'avatar' + (small ? ' sm' : '')} key={index} style={{ background: member.color, color: member.textColor }} aria-hidden="true">
          {member.initial}
        </span>
      ))}
    </div>
  );
}

export function MatchView({ phase, dataVersion, onBack, onNavigate, onToast }) {
  const [capacity, setCapacity] = useState(4);
  const [gameId, setGameId] = useState('');
  const candidates = useCandidates(dataVersion);
  const step = MATCH_PHASES.includes(phase) ? phase : 'idle';
  const members = membersOf(capacity);
  const selected = candidates.games.find((game) => game.id === gameId);
  const gameLabel = selected ? selected.title : '게임 미정';

  return (
    <div className="screen sub">
      <TopBar onBack={onBack} title="실시간 온라인 매칭" />
      <div className="screen-body pad-bottom">
        <p className="screen-lead">사람이 모이면 보드게임아레나 방으로 연결해요. 집에서 바로 하는 한 판이에요.</p>

        {step === 'idle' && (
          <>
            <p className="section-label" style={{ marginTop: 26 }}>하고 싶은 게임 <span className="section-label-note">· 지금 정하지 않아도 되어요</span></p>
            {candidates.error
              ? <div style={{ marginTop: 12 }}><ErrorBox title="게임을 불러오지 못했어요" message={candidates.error} onRetry={candidates.retry} /></div>
              : (
                <div className="chiprow">
                  {candidates.games.map((game) => (
                    <button
                      className={'chip' + (gameId === game.id ? ' on' : '')}
                      type="button"
                      key={game.id}
                      aria-pressed={gameId === game.id}
                      onClick={() => setGameId(gameId === game.id ? '' : game.id)}
                    >
                      {game.title}
                    </button>
                  ))}
                </div>
              )}
            <p className="section-label" style={{ marginTop: 24 }}>인원</p>
            <div className="chiprow">
              {MATCH_CAPACITIES.map((count) => (
                <button
                  className={'chip' + (capacity === count ? ' on' : '')}
                  type="button"
                  key={count}
                  aria-pressed={capacity === count}
                  onClick={() => setCapacity(count)}
                >
                  {count}인
                </button>
              ))}
            </div>
            {/* 매칭은 서버가 사람을 모아야 하므로 실행하지 않고 준비 중임을 알린다. */}
            <button className="btn cta" type="button" style={{ marginTop: 26 }} onClick={() => onToast(P2_NOTICE)}>매칭 시작하기</button>
            <p className="screen-note">사람이 모이면 온라인 방이 열리고, 그 방에서 할 게임을 정해요. 오프라인 모임과는 별개라 내 모임에 쌓이지 않아요.</p>
          </>
        )}

        {step === 'searching' && (
          <section className="match-card">
            <p className="match-lead">사람을 찾는 중</p>
            <h2>{gameLabel} {capacity}인</h2>
            <Members members={members} filled={2} pulsing />
            <button className="btn white" type="button" style={{ marginTop: 22 }} onClick={() => onNavigate('/match')}>매칭 취소</button>
          </section>
        )}

        {step === 'matched' && (
          <>
            <section className="match-card done">
              <p className="match-lead">모집 완료</p>
              <h2>{gameLabel} {capacity}인 모였어요</h2>
              <Avatars members={members} />
            </section>
            <button className="btn cta" type="button" style={{ marginTop: 14 }} onClick={() => onNavigate('/online-room')}>
              온라인 방 들어가기<ArrowIcon size={16} />
            </button>
            <p className="screen-note">방에서 무엇을 할지 같이 정하고, 정해지면 보드게임아레나로 넘어가요.</p>
            <button className="btn fill" type="button" style={{ marginTop: 14 }} onClick={() => onNavigate('/match')}>나가기</button>
          </>
        )}

        {step === 'failed' && (
          <StateBlock
            title={<>{'지금은 사람이 '}<br />{'모이지 않았어요'}</>}
            description="인원을 줄이거나 다른 게임으로 다시 보시면 더 빨리 맞아요."
          >
            <div className="btn-row">
              <button className="btn" type="button" onClick={() => onToast(P2_NOTICE)}>다시 시도</button>
              <button className="btn fill" type="button" onClick={() => onNavigate('/find')}>오프라인 모임 보기</button>
            </div>
          </StateBlock>
        )}
      </div>
    </div>
  );
}

export function OnlineRoomView({ dataVersion, onBack, onToast }) {
  const [draft, setDraft] = useState('');
  const capacity = 4;
  const members = membersOf(capacity);
  const { games, error, retry } = useCandidates(dataVersion);
  // 방에 들어온 사람은 언제나 표가 하나 있다. 고른 게임이 없으면 첫 후보에 둔다.
  const candidates = tally(games, capacity, games[0]?.id || '');
  const lead = candidates.slice().sort((left, right) => right.votes - left.votes)[0] || null;

  return (
    <div className="screen sub">
      <TopBar onBack={onBack} backLabel="온라인 방 나가기" action={(
        <span className="bot-title">
          <strong>온라인 방</strong>
          <span>참가자 {capacity}인 · 보드게임아레나</span>
        </span>
      )} />
      <div className="oroom-head">
        <Avatars members={members} small />
        {error && <div style={{ marginTop: 16 }}><ErrorBox title="게임을 불러오지 못했어요" message={error} onRetry={retry} /></div>}
        {!error && !!candidates.length && (
          <>
            <div className="oroom-votelead">
              <span className="section-label">무엇을 할지 골라주세요</span>
              <span className="caption">지금 1위 · {lead?.title}</span>
            </div>
            {/* 표는 방 참가자 모두에게 공유돼야 하므로 옮기는 것도 서버 몫이다. */}
            <div className="home-starters nos">
              {candidates.map((game) => (
                <button className={'home-starter oroom-cand' + (game.mine ? ' mine' : '')} type="button" key={game.id} aria-pressed={game.mine} onClick={() => onToast(P2_NOTICE)}>
                  <span className="home-starter-tile"><Cover src={game.imageUrl} /></span>
                  <span className="home-starter-name">{game.title}</span>
                  <span className="home-starter-meta">{game.votes}표</span>
                </button>
              ))}
            </div>
            <button className="btn fill sm" type="button" style={{ marginTop: 12 }} onClick={() => onToast(P2_NOTICE)}>{lead?.title}으로 정하기</button>
          </>
        )}
      </div>
      <div className="chat-log">
        {PREVIEW_MESSAGES.map((message, index) => (
          message.system
            ? <p className="oroom-system" key={index}>{message.text}</p>
            : <p className={'bot-msg' + (message.me ? ' me' : '')} key={index}>{message.text}</p>
        ))}
      </div>
      <div className="oroom-foot">
        <form className="oroom-compose" onSubmit={(event) => { event.preventDefault(); onToast(P2_NOTICE); }}>
          <label className="sr-only" htmlFor="oroom-message">보낼 말</label>
          <input id="oroom-message" value={draft} onChange={(event) => setDraft(event.target.value)} placeholder="보낼 말" />
          <button className="chat-send" type="submit" disabled={!draft.trim()} aria-label="보내기"><SendIcon /></button>
        </form>
        <button className="btn off" type="button" onClick={() => onToast(P2_NOTICE)}>
          {lead ? lead.title + ' · 보드게임아레나에서 열기' : '보드게임아레나에서 열기'}
        </button>
      </div>
    </div>
  );
}
