import React, { useEffect, useRef, useState } from 'react';
import { api } from '../api';
import { normalizeGameSummary } from '../game';
import { useRequest } from '../shared/async';
import { playerColor, playerTextColor } from '../shared/players';
import { ArrowIcon, Cover, ErrorBox, SendIcon, StateBlock, TopBar } from '../shared/ui';

/**
 * P2 시안. 담당자 명세가 확정되기 전의 배치 제안이라 매칭 서버 API는 아직 없다.
 *
 * 매칭 방은 오프라인 ROOM과 별개다. 내 모임에 쌓이지 않고 RoomStatus와도 섞지 않는다.
 * 화면 흐름만 보여 주려고 진행 상태는 이 훅 안에서만 오간다. 게임 후보는 공개 조회 API를 읽는다.
 */

export const MATCH_CAPACITIES = [2, 3, 4];
const CANDIDATE_SIZE = 4;
const SEARCH_SECONDS = 4;
const MEMBER_NAMES = ['나', '지현', '현수', '민경'];

const INITIAL_MESSAGES = [{ system: true, text: '온라인 방이 열렸어요. 무엇을 할지 정해보세요.' }];

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

export function useOnlineMatch(dataVersion, active) {
  const [phase, setPhase] = useState('idle');
  const [capacity, setCapacity] = useState(4);
  const [gameId, setGameId] = useState('');
  const [seconds, setSeconds] = useState(0);
  const [myVote, setMyVote] = useState('');
  const [decided, setDecided] = useState('');
  const [messages, setMessages] = useState(INITIAL_MESSAGES);
  const ticker = useRef(0);

  // 시안 화면에 들어갈 때만 후보를 읽는다. 다른 화면에서 앱을 열 때 요청을 늘리지 않는다.
  const games = useRequest(
    (signal) => (active ? api.getGames({ page: 0, size: CANDIDATE_SIZE }, signal) : Promise.resolve({ content: [] })),
    [dataVersion, active]
  );
  const candidates = (games.data?.content || []).map(normalizeGameSummary);

  useEffect(() => () => window.clearInterval(ticker.current), []);

  const stop = (next) => {
    window.clearInterval(ticker.current);
    setSeconds(0);
    setPhase(next);
  };

  const start = () => {
    window.clearInterval(ticker.current);
    setSeconds(0);
    setPhase('searching');
    ticker.current = window.setInterval(() => {
      setSeconds((current) => {
        if (current + 1 < SEARCH_SECONDS) return current + 1;
        window.clearInterval(ticker.current);
        setPhase('matched');
        return current + 1;
      });
    }, 1000);
  };

  const enterRoom = () => {
    setMyVote((current) => current || gameId || candidates[0]?.id || '');
    setDecided('');
    setMessages(INITIAL_MESSAGES);
  };

  const decide = (game) => {
    setDecided(game.id);
    setMessages((current) => current.concat([{ system: true, text: game.title + '으로 정해졌어요.' }]));
  };

  const reopen = () => setDecided('');

  const say = (text) => setMessages((current) => current.concat([{ me: true, text }]));

  return {
    phase,
    capacity,
    setCapacity,
    gameId,
    setGameId,
    seconds,
    candidates,
    candidatesError: games.error,
    retryCandidates: games.retry,
    members: membersOf(capacity),
    selectedGame: candidates.find((game) => game.id === gameId) || null,
    myVote,
    setMyVote,
    decided,
    decidedGame: candidates.find((game) => game.id === decided) || null,
    messages,
    start,
    cancel: () => stop('idle'),
    enterRoom,
    decide,
    reopen,
    say
  };
}

function MatchMembers({ members, filled, pulsing }) {
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

export function MatchView({ match, previewFailed, onBack, onNavigate }) {
  const phase = previewFailed ? 'failed' : match.phase;
  const capLabel = match.capacity + '인';
  const gameLabel = match.selectedGame ? match.selectedGame.title : '게임 미정';
  return (
    <div className="screen sub">
      <TopBar onBack={onBack} />
      <div className="screen-body pad-bottom">
        <h1 className="screen-title">실시간 온라인 매칭</h1>
        <p className="screen-lead">사람이 모이면 보드게임아레나 방으로 연결해요. 집에서 바로 하는 한 판이에요.</p>

        {phase === 'idle' && (
          <>
            <p className="section-label" style={{ marginTop: 26 }}>하고 싶은 게임 <span className="section-label-note">· 지금 정하지 않아도 되어요</span></p>
            {match.candidatesError
              ? <div style={{ marginTop: 12 }}><ErrorBox title="게임을 불러오지 못했어요" message={match.candidatesError} onRetry={match.retryCandidates} /></div>
              : (
                <div className="chiprow">
                  {match.candidates.map((game) => (
                    <button
                      className={'chip' + (match.gameId === game.id ? ' on' : '')}
                      type="button"
                      key={game.id}
                      aria-pressed={match.gameId === game.id}
                      onClick={() => match.setGameId(match.gameId === game.id ? '' : game.id)}
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
                  className={'chip' + (match.capacity === count ? ' on' : '')}
                  type="button"
                  key={count}
                  aria-pressed={match.capacity === count}
                  onClick={() => match.setCapacity(count)}
                >
                  {count}인
                </button>
              ))}
            </div>
            <button className="btn cta" type="button" style={{ marginTop: 26 }} onClick={match.start}>매칭 시작하기</button>
            <p className="screen-note">사람이 모이면 온라인 방이 열리고, 그 방에서 할 게임을 정해요. 오프라인 모임과는 별개라 내 모임에 쌓이지 않아요.</p>
          </>
        )}

        {phase === 'searching' && (
          <section className="match-card" aria-live="polite">
            <p className="match-lead">{match.seconds}초 찾는 중</p>
            <h2>{gameLabel} {capLabel}</h2>
            <MatchMembers members={match.members} filled={Math.min(match.capacity, 1 + match.seconds)} pulsing />
            <button className="btn white" type="button" style={{ marginTop: 22 }} onClick={match.cancel}>매칭 취소</button>
          </section>
        )}

        {phase === 'matched' && (
          <>
            <section className="match-card done">
              <p className="match-lead">모집 완료</p>
              <h2>{gameLabel} {capLabel} 모였어요</h2>
              <div className="match-avatars">
                {match.members.map((member, index) => (
                  <span className="avatar" key={index} style={{ background: member.color, color: member.textColor }} aria-hidden="true">{member.initial}</span>
                ))}
              </div>
            </section>
            <button className="btn cta" type="button" style={{ marginTop: 14 }} onClick={() => { match.enterRoom(); onNavigate('/online-room'); }}>
              온라인 방 들어가기<ArrowIcon size={16} />
            </button>
            <p className="screen-note">방에서 무엇을 할지 같이 정하고, 정해지면 보드게임아레나로 넘어가요.</p>
            <button className="btn fill" type="button" style={{ marginTop: 14 }} onClick={match.cancel}>나가기</button>
          </>
        )}

        {phase === 'failed' && (
          <StateBlock
            title={<>{'지금은 사람이 '}<br />{'모이지 않았어요'}</>}
            description="인원을 줄이거나 다른 게임으로 다시 보시면 더 빨리 맞아요."
          >
            <div className="btn-row">
              <button className="btn" type="button" onClick={match.start}>다시 시도</button>
              <button className="btn fill" type="button" onClick={() => onNavigate('/find')}>오프라인 모임 보기</button>
            </div>
          </StateBlock>
        )}
      </div>
    </div>
  );
}

export function OnlineRoomView({ match, onBack, onToast }) {
  const [draft, setDraft] = useState('');
  // 방에 들어온 사람은 언제나 표가 하나 있다. 고른 게임이 없으면 첫 후보에 둔다.
  const myVote = match.myVote || match.candidates[0]?.id || '';
  const candidates = tally(match.candidates, match.capacity, myVote);
  const lead = candidates.slice().sort((left, right) => right.votes - left.votes)[0] || null;
  const decidedGame = match.decidedGame;

  const send = (event) => {
    event.preventDefault();
    const text = draft.trim();
    if (!text) return;
    match.say(text);
    setDraft('');
  };

  return (
    <div className="screen sub">
      <TopBar onBack={onBack} backLabel="온라인 방 나가기" action={(
        <span className="bot-title">
          <strong>온라인 방</strong>
          <span>참가자 {match.capacity}인 · 보드게임아레나</span>
        </span>
      )} />
      <div className="oroom-head">
        <div className="match-avatars">
          {match.members.map((member, index) => (
            <span className="avatar sm" key={index} style={{ background: member.color, color: member.textColor }} aria-hidden="true">{member.initial}</span>
          ))}
        </div>
        {decidedGame && (
          <div className="oroom-decided">
            <span>
              <b>정해진 게임</b>
              <strong>{decidedGame.title}</strong>
            </span>
            <button type="button" onClick={match.reopen}>다시 정하기</button>
          </div>
        )}
        {!decidedGame && !!candidates.length && (
          <>
            <div className="oroom-votelead">
              <span className="section-label">무엇을 할지 골라주세요</span>
              <span className="caption">지금 1위 · {lead?.title}</span>
            </div>
            <div className="home-starters nos">
              {candidates.map((game) => (
                <button className={'home-starter oroom-cand' + (game.mine ? ' mine' : '')} type="button" key={game.id} aria-pressed={game.mine} onClick={() => match.setMyVote(game.id)}>
                  <span className="home-starter-tile"><Cover src={game.imageUrl} /></span>
                  <span className="home-starter-name">{game.title}</span>
                  <span className="home-starter-meta">{game.votes}표</span>
                </button>
              ))}
            </div>
            <button className="btn fill sm" type="button" style={{ marginTop: 12 }} onClick={() => lead && match.decide(lead)}>{lead?.title}으로 정하기</button>
          </>
        )}
      </div>
      <div className="chat-log">
        {match.messages.map((message, index) => (
          message.system
            ? <p className="oroom-system" key={index}>{message.text}</p>
            : <p className={'bot-msg' + (message.me ? ' me' : '')} key={index}>{message.text}</p>
        ))}
      </div>
      <div className="oroom-foot">
        <form className="oroom-compose" onSubmit={send}>
          <label className="sr-only" htmlFor="oroom-message">보낼 말</label>
          <input id="oroom-message" value={draft} onChange={(event) => setDraft(event.target.value)} placeholder="보낼 말" />
          <button className="chat-send" type="submit" disabled={!draft.trim()} aria-label="보내기"><SendIcon /></button>
        </form>
        <button
          className={'btn' + (decidedGame ? '' : ' off')}
          type="button"
          disabled={!decidedGame}
          onClick={() => onToast(decidedGame.title + ' 방으로 넘어가요. 아직 시안이라 실제로 열리지는 않아요.')}
        >
          {decidedGame ? decidedGame.title + ' · 보드게임아레나에서 열기' : '게임을 먼저 정해주세요'}
        </button>
      </div>
    </div>
  );
}
