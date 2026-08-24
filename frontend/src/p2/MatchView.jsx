import React, { useCallback, useEffect, useRef, useState } from 'react';
import mascotCut from '../../assets/mascot-cut.png';
import { ApiError, api, messageForError } from '../api';
import { usePaginatedRequest } from '../shared/async';
import { playerColor } from '../shared/players';
import { Avatar, BackIcon, CheckIcon, ChevronDownIcon, ChevronUpIcon, ErrorBox, InfoIcon, Pagination, PersonSilhouetteIcon, SendIcon, StateBlock, TopBar, UsersIcon } from '../shared/ui';

/**
 * MATCH-01 실시간 파티 매칭. 서버가 현재 상태의 정본이라 화면은 주소가 아니라
 * `GET /api/matches/current`가 돌려주는 state로만 갈린다. 진행 단계는 폴링으로 따라간다.
 */

const MATCH_POLL_INTERVAL_MS = 3500;
const MATCH_POLLING_STATES = ['WAITING', 'PROPOSED', 'PAUSED', 'PREPARING'];

function createIdempotencyKey() {
  return globalThis.crypto?.randomUUID?.() || 'match-' + Date.now() + '-' + Math.random().toString(36).slice(2);
}

function partySizeLabel(minPlayers, maxPlayers) {
  return minPlayers === maxPlayers ? minPlayers + '인' : minPlayers + '~' + maxPlayers + '인';
}

const KOREAN_COUNT = { 1: '한', 2: '두', 3: '세', 4: '네', 5: '다섯', 6: '여섯', 7: '일곱', 8: '여덟' };
function partyCountLabel(count) {
  return (KOREAN_COUNT[count] || count) + ' 명';
}

function useElapsed(sinceIso) {
  const compute = () => Math.max(0, Date.now() - new Date(sinceIso));
  const [elapsedMs, setElapsedMs] = useState(compute);
  useEffect(() => {
    setElapsedMs(compute());
    const timer = setInterval(() => setElapsedMs(compute()), 1000);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sinceIso]);
  return elapsedMs;
}

function formatElapsed(elapsedMs) {
  const totalSeconds = Math.floor(elapsedMs / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return minutes > 0 ? minutes + '분 ' + seconds + '초' : seconds + '초';
}

function useCurrentMatch() {
  const [snapshot, setSnapshot] = useState({ data: null, loading: true, error: '' });

  const load = useCallback((signal) => api.getCurrentMatch(signal)
    .then((data) => setSnapshot({ data, loading: false, error: '' }))
    .catch((error) => {
      if (error?.name === 'AbortError') return;
      setSnapshot((current) => ({
        data: current.data,
        loading: false,
        error: messageForError(error)
      }));
    }), []);

  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal);
    return () => controller.abort();
  }, [load]);

  const state = snapshot.data?.state || null;
  // 실시간 이벤트가 없으므로 전이 가능한 상태에서만 짧은 간격으로 다시 물어본다.
  useEffect(() => {
    if (!MATCH_POLLING_STATES.includes(state)) return undefined;
    const timer = setInterval(() => load(new AbortController().signal), MATCH_POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [state, load]);

  return { data: snapshot.data, loading: snapshot.loading, error: snapshot.error, reload: load };
}

const PARTY_SIZE_OPTIONS = [2, 3, 4, 5, 6, 7, 8, 9, 10];

function MatchCountSelect({ label, value, onChange }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="match-select-col">
      <span>{label}</span>
      <button type="button" className={'match-select-trigger' + (open ? ' on' : '')} onClick={() => setOpen(!open)}>
        <b>{value}명</b>
        {open ? <ChevronUpIcon size={18} /> : <ChevronDownIcon size={18} />}
      </button>
      {open && (
        <div className="match-select-list">
          <p className="match-select-list-label">{label} 인원</p>
          {PARTY_SIZE_OPTIONS.map((count) => (
            <button
              key={count}
              type="button"
              className={'match-select-option' + (count === value ? ' on' : '')}
              onClick={() => { onChange(count); setOpen(false); }}
            >
              {count}명
              {count === value && <CheckIcon size={17} width={2.6} />}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function MatchRequestForm({ onSubmitted, onToast }) {
  const [min, setMin] = useState(2);
  const [max, setMax] = useState(4);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  const changeMin = (value) => {
    setMin(value);
    if (value > max) setMax(value);
  };
  const changeMax = (value) => {
    setMax(value);
    if (value < min) setMin(value);
  };

  const submit = async () => {
    setSubmitting(true);
    setError('');
    try {
      await api.createMatchRequest({ minPlayers: min, maxPlayers: max }, createIdempotencyKey());
      onSubmitted();
    } catch (cause) {
      if (cause instanceof ApiError && cause.code === 'MATCH_REQUEST_ALREADY_ACTIVE') {
        onSubmitted();
        onToast('이미 진행 중인 매칭이 있어요.', 'err');
      } else {
        setError(messageForError(cause));
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <>
      <div className="match-scroll">
        <p className="section-label" style={{ marginTop: 26 }}>몇 명이서 할까요</p>
        <div className="match-select-row">
          <MatchCountSelect label="최소" value={min} onChange={changeMin} />
          <span className="match-select-sep">–</span>
          <MatchCountSelect label="최대" value={max} onChange={changeMax} />
        </div>
        <div className="match-hint">
          <InfoIcon size={17} />
          <p>나와 원하는 인원대가 겹치는 사람끼리 묶여요. 범위를 넓게 잡으면 더 빨리 만나요.</p>
        </div>
        {error && <div style={{ marginTop: 16 }}><ErrorBox message={error} onRetry={submit} /></div>}
        <p className="screen-note">오프라인 모임과는 별개라 내 모임에 쌓이지 않아요.</p>
      </div>
      <div className="match-footer">
        <button className="btn cta" type="button" disabled={submitting} onClick={submit}>
          {submitting ? '요청하는 중…' : '매칭 시작하기'}
        </button>
      </div>
    </>
  );
}

function MatchWaiting({ request, onChanged, onToast }) {
  const [canceling, setCanceling] = useState(false);
  const elapsed = useElapsed(request.queuedAt);
  const cancel = async () => {
    setCanceling(true);
    try {
      await api.cancelMatchRequest();
      onChanged();
    } catch (cause) {
      onToast(messageForError(cause), 'err');
    } finally {
      setCanceling(false);
    }
  };
  return (
    <>
      <div className="match-center">
        <h2 className="match-headline">{partySizeLabel(request.minPlayers, request.maxPlayers)}로<br />사람을 찾고 있어요</h2>
        <p className="match-status-dot"><i />원하는 인원대가 겹치는 사람을 찾는 중</p>
        <p className="match-elapsed">기다린 시간 {formatElapsed(elapsed)}</p>
        <div className="match-hint">
          <img src={mascotCut} alt="" />
          <p>기다리는 시간에 제한은 없어요. 사람이 모이면 채팅방이 열리고, 할 게임은 거기서 정해요.</p>
        </div>
      </div>
      <div className="match-footer plain">
        <button className="btn fill" type="button" disabled={canceling} onClick={cancel}>
          {canceling ? '취소하는 중…' : '매칭 취소'}
        </button>
        <p className="screen-note" style={{ margin: 0, textAlign: 'center' }}>화면을 닫아도 매칭은 계속돼요. 모이면 알림으로 알려드려요.</p>
      </div>
    </>
  );
}

function MatchPaused({ request, onChanged, onToast }) {
  const [pending, setPending] = useState('');

  const retry = async () => {
    setPending('retry');
    try {
      await api.cancelMatchRequest();
      await api.createMatchRequest({ minPlayers: request.minPlayers, maxPlayers: request.maxPlayers }, createIdempotencyKey());
      onChanged();
    } catch (cause) {
      onToast(messageForError(cause), 'err');
    } finally {
      setPending('');
    }
  };

  const cancel = async () => {
    setPending('cancel');
    try {
      await api.cancelMatchRequest();
      onChanged();
    } catch (cause) {
      onToast(messageForError(cause), 'err');
    } finally {
      setPending('');
    }
  };

  return (
    <section className="match-card">
      <p className="match-lead">잠시 멈췄어요</p>
      <h2>응답 시간이 지났어요</h2>
      <p className="screen-note">다시 찾기를 누르면 새로 대기를 시작해요.</p>
      <div className="btn-row" style={{ marginTop: 22 }}>
        <button className="btn fill" type="button" disabled={Boolean(pending)} onClick={cancel}>
          {pending === 'cancel' ? '취소하는 중…' : '매칭 취소'}
        </button>
        <button className="btn" type="button" disabled={Boolean(pending)} onClick={retry}>
          {pending === 'retry' ? '다시 찾는 중…' : '다시 찾기'}
        </button>
      </div>
    </section>
  );
}

function useCountdown(deadlineIso) {
  const compute = () => Math.max(0, new Date(deadlineIso) - Date.now());
  const [remainingMs, setRemainingMs] = useState(compute);
  useEffect(() => {
    setRemainingMs(compute());
    const timer = setInterval(() => setRemainingMs(compute()), 1000);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deadlineIso]);
  return remainingMs;
}

const PROPOSAL_RESPOND_WINDOW_MS = 30000;

function MatchProposed({ proposal, onChanged, onToast }) {
  const remainingMs = useCountdown(proposal.respondBy);
  const remainingSeconds = Math.max(0, Math.ceil(remainingMs / 1000));
  const progress = Math.max(0, Math.min(100, (remainingMs / PROPOSAL_RESPOND_WINDOW_MS) * 100));
  const [pending, setPending] = useState('');
  const accepted = proposal.myResponse === 'ACCEPTED';
  const disabled = accepted || Boolean(pending);

  const respond = async (action) => {
    setPending(action);
    try {
      await api.respondToMatchProposal(proposal.proposalId, action, createIdempotencyKey());
      onChanged();
    } catch (cause) {
      onToast(messageForError(cause), 'err');
    } finally {
      setPending('');
    }
  };

  return (
    <>
      <div className="match-center">
        <div className="match-countdown"><b>{remainingSeconds}</b><span>초 안에 답해 주세요</span></div>
        <div className="match-progress"><i style={{ width: progress + '%' }} /></div>
        <h2 className="match-headline">{partyCountLabel(proposal.partySize)} 모였어요<br />같이 하실래요?</h2>
        <p className="screen-note">모두 수락하면 채팅방이 열리고 서로 닉네임이 보여요.</p>
        {/* 전원 수락 전에는 닉네임·사진을 보여주지 않는다. 익명 인원 수만 표시한다. */}
        <div className="match-anon-avatars" aria-hidden="true">
          {proposal.members.map((member, index) => (
            <span className="match-anon-avatar" key={index}><PersonSilhouetteIcon size={26} /></span>
          ))}
        </div>
        {accepted && <p className="screen-note" style={{ marginTop: 18 }}>다른 인원을 기다리는 중이에요.</p>}
      </div>
      <div className="match-footer plain">
        <button className="btn cta" type="button" disabled={disabled} onClick={() => respond('ACCEPT')}>
          {accepted ? '수락함' : pending === 'ACCEPT' ? '수락하는 중…' : '수락'}
        </button>
        <button className="btn fill" type="button" disabled={disabled} onClick={() => respond('REQUEUE')}>
          {pending === 'REQUEUE' ? '처리 중…' : '건너뛰고 다시 기다리기'}
        </button>
        <button className="btn-text-link" type="button" disabled={disabled} onClick={() => respond('CANCEL')}>
          {pending === 'CANCEL' ? '처리 중…' : '매칭 취소'}
        </button>
      </div>
    </>
  );
}

function MatchPreparing({ preparing }) {
  const members = preparing.members;
  return (
    <>
      <div className="match-center">
        <div className="match-accepted-label">
          <span className="match-check" aria-hidden="true"><CheckIcon size={18} width={3} /></span>
          {partyCountLabel(members.length)} 모두 수락했어요
        </div>
        <div className="match-progress done"><i style={{ width: '100%' }} /></div>
        <h2 className="match-headline">채팅방을 열고 있어요</h2>
        <p className="screen-note">이제 서로 닉네임이 보여요.</p>
        <div className="match-roster">
          {members.map((member, index) => (
            <div className="match-roster-col" key={index}>
              <Avatar name={member.nickname} imageUrl={member.profileImageUrl} index={index} />
              <strong>{member.nickname}</strong>
              {member.isMine && <span>나</span>}
            </div>
          ))}
        </div>
        <div className="match-hint">
          <InfoIcon size={17} />
          <p>채팅방은 24시간 뒤 문을 닫아요. 할 게임은 채팅에서 정하고 보드게임아레나에서 같이 해요.</p>
        </div>
      </div>
      <div className="match-footer plain">
        <div className="match-status-pill">
          <i />
          <b>준비되면 자동으로 들어가요</b>
          <span>보통 10초</span>
        </div>
      </div>
    </>
  );
}

export function MatchView({ onBack, onNavigate, onToast }) {
  const { data, loading, error, reload } = useCurrentMatch();
  const state = data?.state || null;

  useEffect(() => {
    if (state === 'ACTIVE') onNavigate('/match-chat', { replace: true });
    // onNavigate(useHashRoute의 navigate)는 App이 다시 그려질 때마다 새로 만들어진다.
    // 매번 새 참조로 이 effect가 다시 돌면 관련 없는 재렌더링마다 이동을 반복 시도하므로 state에만 반응한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state]);

  const hasTopBar = !['PROPOSED', 'PREPARING'].includes(state);
  const flexBody = !state || ['WAITING', 'PROPOSED', 'PREPARING'].includes(state);

  return (
    <div className="screen sub">
      {hasTopBar && (
        state === 'WAITING'
          ? <TopBar onBack={onBack} closeIcon />
          : <TopBar onBack={onBack} title="온라인 매칭" />
      )}
      <div className={'screen-body pad-bottom' + (hasTopBar ? '' : ' pad-top') + (flexBody ? ' match-flex' : '')}>
        {!state && !loading && (
          <p className="screen-lead" style={{ flex: 'none' }}>사람이 모이면 채팅방이 열려요. 할 게임을 정하고 보드게임아레나에서 같이 해요.</p>
        )}
        {error && !data && <div style={{ marginTop: 16 }}><ErrorBox message={error} onRetry={() => reload()} /></div>}
        {error && data && <p className="screen-note" role="status">잠시 연결이 불안정해 이전 상태를 보여드리며 다시 확인하고 있어요.</p>}
        {!error && loading && !data && <p className="screen-note">불러오는 중…</p>}
        {!error && !loading && !state && <MatchRequestForm onSubmitted={() => reload()} onToast={onToast} />}
        {data && state === 'WAITING' && <MatchWaiting request={data.request} onChanged={() => reload()} onToast={onToast} />}
        {data && state === 'PAUSED' && <MatchPaused request={data.request} onChanged={() => reload()} onToast={onToast} />}
        {data && state === 'PROPOSED' && <MatchProposed proposal={data.proposal} onChanged={() => reload()} onToast={onToast} />}
        {data && state === 'PREPARING' && <MatchPreparing preparing={data.preparing} />}
      </div>
    </div>
  );
}

// ---- MATCH 채팅 ----

const MATCH_CHAT_RECONNECT_LIMIT = 3;
const MS_PER_HOUR = 60 * 60 * 1000;
const MS_PER_MINUTE = 60 * 1000;

function blurChatInputOnSurfacePointerDown(event) {
  if (event.target?.closest?.('.chat-compose')) return;
  const activeElement = document.activeElement;
  if (activeElement?.matches?.('input, textarea')) activeElement.blur();
}

function preserveChatInputFocusOnSendPointerDown(event) {
  event.preventDefault();
}

function formatRemainingClose(closesAtIso) {
  const remainingMs = new Date(closesAtIso) - Date.now();
  if (remainingMs <= 0) return '곧 문을 닫아요';
  const hours = Math.floor(remainingMs / MS_PER_HOUR);
  if (hours > 0) return hours + '시간 뒤 문을 닫아요';
  const minutes = Math.max(1, Math.floor(remainingMs / MS_PER_MINUTE));
  return minutes + '분 뒤 문을 닫아요';
}

function matchChatStreamMessage(payload, partyId) {
  if (!payload || payload.type !== 'MESSAGE_CREATED' || !payload.message) return null;
  if (String(payload.message.partyId) !== String(partyId) || payload.message.messageId === undefined) return null;
  if (Number(payload.eventId) !== Number(payload.message.messageId)) return null;
  return payload.message;
}

function mergeMatchChatMessages(current, incoming) {
  const byId = new Map(current.map((message) => [String(message.messageId), message]));
  incoming.forEach((message) => byId.set(String(message.messageId), message));
  return [...byId.values()].sort((left, right) => Number(left.messageId) - Number(right.messageId));
}

const REPORT_REASONS = [
  { value: 'ABUSE_OR_HARASSMENT', label: '학대/괴롭힘' },
  { value: 'HATE_OR_DISCRIMINATION', label: '혐오/차별' },
  { value: 'SEXUAL_CONTENT', label: '성적 콘텐츠' },
  { value: 'SPAM_OR_SCAM', label: '스팸/사기' },
  { value: 'OTHER_RULE_VIOLATION', label: '그 밖의 위반' }
];

function MatchRosterSheet({ partyId, members, onClose, onToast, onLeave }) {
  const [reportTarget, setReportTarget] = useState(null);
  const [busyRef, setBusyRef] = useState('');

  const block = async (participantRef) => {
    setBusyRef(participantRef);
    try {
      await api.blockMatchParticipant(partyId, participantRef);
      onToast('차단했어요.');
    } catch (cause) {
      onToast(messageForError(cause), 'err');
    } finally {
      setBusyRef('');
    }
  };

  const report = async (participantRef, reason) => {
    setBusyRef(participantRef);
    try {
      await api.reportMatchParticipant(partyId, { participantRef, reason });
      onToast('신고를 접수했어요.');
      setReportTarget(null);
    } catch (cause) {
      onToast(messageForError(cause), 'err');
    } finally {
      setBusyRef('');
    }
  };

  return (
    <div className="sheet-backdrop" role="presentation" onMouseDown={onClose}>
      <section className="sheet" role="dialog" aria-modal="true" aria-label="참가자" onMouseDown={(event) => event.stopPropagation()}>
        <div className="sheet-head">
          <h2>참가자</h2>
          <button type="button" className="sheet-close" aria-label="닫기" onClick={onClose}>✕</button>
        </div>
        <div className="menu-list" style={{ padding: '0 var(--pad) 22px' }}>
          {members.filter((member) => !member.isMine).map((member) => (
            <div className="menu-row" key={member.participantRef} style={{ flexWrap: 'wrap' }}>
              <Avatar imageUrl={member.profileImageUrl} name={member.nickname} />
              <span className="menu-row-label">{member.nickname}</span>
              <button type="button" className="btn fill sm" disabled={busyRef === member.participantRef} onClick={() => block(member.participantRef)}>차단</button>
              <button
                type="button"
                className="btn fill sm"
                disabled={busyRef === member.participantRef}
                onClick={() => setReportTarget(reportTarget === member.participantRef ? null : member.participantRef)}
              >
                신고
              </button>
              {reportTarget === member.participantRef && (
                <div className="chiprow" style={{ width: '100%', marginTop: 10 }}>
                  {REPORT_REASONS.map((reason) => (
                    <button
                      key={reason.value}
                      type="button"
                      className="chip"
                      disabled={busyRef === member.participantRef}
                      onClick={() => report(member.participantRef, reason.value)}
                    >
                      {reason.label}
                    </button>
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>
        <div style={{ padding: '0 var(--pad) 22px' }}>
          <button type="button" className="btn fill" onClick={onLeave}>채팅방 나가기</button>
        </div>
      </section>
    </div>
  );
}

export function MatchChatView({ onBack, onNavigate, onToast }) {
  const [handoff, setHandoff] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const partyId = handoff?.partyId;

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    api.getCurrentMatch(controller.signal)
      .then((data) => {
        if (!active) return;
        if (data?.state === 'ACTIVE' && data.chat) {
          setHandoff(data.chat);
          setLoading(false);
        } else {
          // 채팅에 접근할 수 없는 최신 상태면 매칭 화면이 실제 상태를 다시 보여준다.
          onNavigate('/match', { replace: true });
        }
      })
      .catch((cause) => {
        if (!active || cause?.name === 'AbortError') return;
        setError(messageForError(cause));
        setLoading(false);
      });
    return () => { active = false; controller.abort(); };
    // onNavigate는 App 재렌더링마다 새로 만들어진다. 여기 넣으면 매칭과 무관한 재렌더링마다
    // 조회를 반복해 늦게 도착한 응답이 먼저 도착한 ACTIVE 결과를 덮어쓸 수 있어 최초 1회만 조회한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const [messages, setMessages] = useState([]);
  const [nextBeforeMessageId, setNextBeforeMessageId] = useState(null);
  const [hasNext, setHasNext] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [content, setContent] = useState('');
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState('');
  const [rosterOpen, setRosterOpen] = useState(false);
  const [chatClosed, setChatClosed] = useState(false);
  const [socketGeneration, setSocketGeneration] = useState(0);
  const lastEventIdRef = useRef(null);
  const connectedPartyRef = useRef(null);
  const reconnectAttemptsRef = useRef(0);
  const checkingChatRef = useRef(false);
  const navigateRef = useRef(onNavigate);
  const chatLogRef = useRef(null);
  navigateRef.current = onNavigate;

  const refreshCurrentMatch = useCallback(async ({ reconnect = false } = {}) => {
    if (checkingChatRef.current) return null;
    checkingChatRef.current = true;
    setChatClosed(true);
    try {
      const data = await api.getCurrentMatch();
      if (data?.state === 'ACTIVE' && data.chat) {
        setHandoff(data.chat);
        setError('');
        if (reconnect) {
          setChatClosed(false);
          setSocketGeneration((current) => current + 1);
        } else {
          setError('채팅 연결이 끊겼어요. 다시 시도해 주세요.');
        }
        return data;
      }
      navigateRef.current('/match', { replace: true });
      return null;
    } catch (cause) {
      if (cause?.name !== 'AbortError') setError(messageForError(cause));
      return null;
    } finally {
      checkingChatRef.current = false;
    }
  }, []);

  useEffect(() => {
    if (!handoff?.closesAt || !partyId) return undefined;
    const remainingMs = new Date(handoff.closesAt).getTime() - Date.now();
    if (remainingMs <= 0) {
      refreshCurrentMatch({ reconnect: true });
      return undefined;
    }
    const timer = setTimeout(() => refreshCurrentMatch({ reconnect: true }), remainingMs);
    return () => clearTimeout(timer);
  }, [handoff?.closesAt, partyId, refreshCurrentMatch]);

  useEffect(() => {
    if (!partyId || chatClosed) return undefined;
    let active = true;
    let socket;
    if (connectedPartyRef.current !== partyId) {
      connectedPartyRef.current = partyId;
      lastEventIdRef.current = null;
      reconnectAttemptsRef.current = 0;
    }

    const connect = () => {
      if (!active) return;
      let currentSocket;
      try {
        currentSocket = api.openMatchChatWebSocket(partyId, { afterMessageId: lastEventIdRef.current });
        socket = currentSocket;
      } catch {
        return;
      }
      currentSocket.onopen = () => { reconnectAttemptsRef.current = 0; };
      currentSocket.onmessage = (event) => {
        if (!active) return;
        let payload;
        try {
          payload = JSON.parse(event.data);
        } catch {
          return;
        }
        const message = matchChatStreamMessage(payload, partyId);
        if (!message) return;
        lastEventIdRef.current = Math.max(lastEventIdRef.current || 0, Number(payload.eventId));
        setMessages((current) => mergeMatchChatMessages(current, [message]));
      };
      currentSocket.onclose = () => {
        if (!active) return;
        const reconnect = reconnectAttemptsRef.current < MATCH_CHAT_RECONNECT_LIMIT;
        if (reconnect) reconnectAttemptsRef.current += 1;
        refreshCurrentMatch({ reconnect });
      };
    };

    api.getMatchChatMessages(partyId)
      .then((page) => {
        if (!active) return;
        const latest = (page.messages || []).reduce((max, message) => Math.max(max, Number(message.messageId) || 0), 0);
        if (latest > 0) lastEventIdRef.current = Math.max(lastEventIdRef.current || 0, latest);
        setMessages((current) => mergeMatchChatMessages(current, page.messages || []));
        setNextBeforeMessageId(page.nextBeforeMessageId ?? null);
        setHasNext(Boolean(page.hasNext));
        connect();
      })
      .catch((cause) => {
        if (!active) return;
        setError(messageForError(cause));
        connect();
      });
    return () => {
      active = false;
      socket?.close();
    };
  }, [chatClosed, partyId, refreshCurrentMatch, socketGeneration]);

  useEffect(() => {
    const log = chatLogRef.current;
    if (log) log.scrollTop = log.scrollHeight;
  }, [messages.length]);

  const loadPrevious = async () => {
    if (!hasNext || loadingMore || nextBeforeMessageId === null) return;
    setLoadingMore(true);
    try {
      const page = await api.getMatchChatMessages(partyId, { beforeMessageId: nextBeforeMessageId, size: 50 });
      setMessages((current) => mergeMatchChatMessages(current, page.messages || []));
      setNextBeforeMessageId(page.nextBeforeMessageId ?? null);
      setHasNext(Boolean(page.hasNext));
    } catch (cause) {
      setSendError(messageForError(cause, '이전 메시지를 불러오지 못했어요.'));
    } finally {
      setLoadingMore(false);
    }
  };

  const submit = async (event) => {
    event.preventDefault();
    if (chatClosed) return;
    const trimmed = content.trim();
    if (!trimmed) return;
    setSending(true);
    setSendError('');
    try {
      const saved = await api.sendMatchChatMessage(partyId, { clientMessageId: createIdempotencyKey(), content: trimmed });
      setMessages((current) => mergeMatchChatMessages(current, [saved]));
      setContent('');
    } catch (cause) {
      setSendError(messageForError(cause, '메시지를 보내지 못했어요.'));
    } finally {
      setSending(false);
    }
  };

  const leave = async () => {
    if (!window.confirm('채팅방에서 나갈까요? 나가면 다시 들어올 수 없어요.')) return;
    try {
      await api.leaveMatchParty(partyId);
      onNavigate('/match', { replace: true });
    } catch (cause) {
      onToast(messageForError(cause), 'err');
    }
  };

  if (loading) {
    return (
      <div className="screen sub">
        <TopBar onBack={onBack} title="온라인 매칭" />
        <div className="screen-body pad-bottom"><p className="screen-note">불러오는 중…</p></div>
      </div>
    );
  }
  if (error || !handoff) {
    return (
      <div className="screen sub">
        <TopBar onBack={onBack} title="온라인 매칭" />
        <div className="screen-body pad-bottom"><ErrorBox message={error || '채팅을 열 수 없어요.'} /></div>
      </div>
    );
  }

  const memberByRef = new Map(handoff.members.map((member, index) => [member.participantRef, { ...member, index }]));

  return (
    <div className="chat-screen" onClick={blurChatInputOnSurfacePointerDown}>
      <div className="chat-topbar">
        <button type="button" className="icon-btn" aria-label="뒤로 가기" onClick={onBack}><BackIcon /></button>
        <div className="chat-topbar-copy">
          <strong>온라인 매칭 · {handoff.members.length}명</strong>
          <span>{formatRemainingClose(handoff.closesAt)}</span>
        </div>
        <button type="button" className="icon-btn" aria-label="참가자" onClick={() => setRosterOpen(true)}><UsersIcon /></button>
      </div>

      <div className="chat-log" ref={chatLogRef}>
        {hasNext && (
          <button type="button" className="more-btn" disabled={loadingMore} onClick={loadPrevious}>
            {loadingMore ? '불러오는 중…' : '이전 메시지 더 보기'}
          </button>
        )}
        {!messages.length && <p className="chat-empty">아직 메시지가 없어요.</p>}
        {messages.map((message) => {
          if (message.type === 'SYSTEM') return <p className="chat-system" key={message.messageId}>{message.content}</p>;
          const member = memberByRef.get(message.sender?.participantRef);
          const tone = playerColor(member?.index ?? 0);
          return (
            <div className={'chat-message ' + (message.isMine ? 'mine' : 'theirs')} key={message.messageId}>
              {!message.isMine && (
                <span className="chat-sender">
                  <Avatar name={message.sender?.nickname} imageUrl={member?.profileImageUrl} color={tone} />
                  <b style={{ color: tone }}>{message.sender?.nickname}</b>
                </span>
              )}
              <span className="chat-line">
                <span className="chat-content">{message.content}</span>
              </span>
            </div>
          );
        })}
      </div>

      <form className="chat-compose" onSubmit={submit}>
        <label className="sr-only" htmlFor="match-chat-message">메시지</label>
        <input id="match-chat-message" value={content} onChange={(event) => setContent(event.target.value)} readOnly={sending} aria-busy={sending} disabled={chatClosed} placeholder="메시지 입력" />
        <button className="chat-send" onPointerDown={preserveChatInputFocusOnSendPointerDown} type="submit" disabled={chatClosed || sending || !content.trim()} aria-label="보내기"><SendIcon /></button>
      </form>
      {chatClosed && !error && <p className="chat-fail" role="status" style={{ margin: '0 18px 14px' }}>채팅방 상태를 확인하고 있어요.</p>}
      {sendError && <div className="chat-fail" style={{ margin: '0 18px 14px' }} role="alert"><span>{sendError}</span></div>}

      {rosterOpen && (
        <MatchRosterSheet
          partyId={partyId}
          members={handoff.members}
          onClose={() => setRosterOpen(false)}
          onToast={onToast}
          onLeave={leave}
        />
      )}
    </div>
  );
}

// ---- 차단 목록 ----

export function MatchBlockListView({ onBack, onToast }) {
  const { data, loading, error, setPage, retry } = usePaginatedRequest(
    (page, signal) => api.getMatchBlocks({ page, size: 10 }, signal),
    []
  );
  const [unblockingId, setUnblockingId] = useState(null);
  const list = data?.content || [];

  const unblock = async (blockId) => {
    setUnblockingId(blockId);
    try {
      await api.unblockMatchUser(blockId);
      retry();
    } catch (cause) {
      onToast(messageForError(cause), 'err');
    } finally {
      setUnblockingId(null);
    }
  };

  return (
    <div className="screen sub">
      <TopBar onBack={onBack} title="차단 목록" />
      <div className="screen-body pad-bottom">
        {error && <div style={{ marginTop: 16 }}><ErrorBox message={error} onRetry={retry} /></div>}
        {!error && loading && !list.length && <p className="screen-note">불러오는 중…</p>}
        {!error && !loading && !list.length && (
          <div style={{ marginTop: 22 }}>
            <StateBlock title="차단한 사용자가 없어요" description="매칭 채팅에서 참가자를 차단하면 여기에 나타나요." />
          </div>
        )}
        {!!list.length && (
          <div className="menu-list" style={{ marginTop: 12 }}>
            {list.map((item) => (
              <div className="menu-row" key={item.blockId}>
                <Avatar imageUrl={item.blockedUser.profileImageUrl} name={item.blockedUser.nickname} />
                <span className="menu-row-label">{item.blockedUser.nickname}</span>
                <button type="button" className="btn fill sm" disabled={unblockingId === item.blockId} onClick={() => unblock(item.blockId)}>
                  {unblockingId === item.blockId ? '해제하는 중…' : '차단 해제'}
                </button>
              </div>
            ))}
          </div>
        )}
        <Pagination page={data?.page ?? 0} totalPages={data?.totalPages} loading={loading} onChange={setPage} />
      </div>
    </div>
  );
}
