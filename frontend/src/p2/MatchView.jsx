import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, api, messageForError } from '../api';
import { usePaginatedRequest } from '../shared/async';
import { playerColor } from '../shared/players';
import { Avatar, BackIcon, ErrorBox, InfoIcon, Pagination, SendIcon, StateBlock, TopBar } from '../shared/ui';

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

function useCurrentMatch() {
  const [snapshot, setSnapshot] = useState({ data: null, loading: true, error: '' });

  const load = useCallback((signal) => api.getCurrentMatch(signal)
    .then((data) => setSnapshot({ data, loading: false, error: '' }))
    .catch((error) => {
      if (error?.name === 'AbortError') return;
      setSnapshot({ data: null, loading: false, error: messageForError(error) });
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

const PARTY_SIZE_PRESETS = [
  { min: 2, max: 2 },
  { min: 2, max: 4 },
  { min: 3, max: 4 },
  { min: 4, max: 4 }
];

function MatchRequestForm({ onSubmitted, onToast }) {
  const [range, setRange] = useState(PARTY_SIZE_PRESETS[1]);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  const submit = async () => {
    setSubmitting(true);
    setError('');
    try {
      await api.createMatchRequest({ minPlayers: range.min, maxPlayers: range.max }, createIdempotencyKey());
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
      <p className="section-label" style={{ marginTop: 26 }}>원하는 인원</p>
      <div className="chiprow">
        {PARTY_SIZE_PRESETS.map((preset) => {
          const on = range.min === preset.min && range.max === preset.max;
          return (
            <button
              className={'chip' + (on ? ' on' : '')}
              type="button"
              key={preset.min + '-' + preset.max}
              aria-pressed={on}
              onClick={() => setRange(preset)}
            >
              {partySizeLabel(preset.min, preset.max)}
            </button>
          );
        })}
      </div>
      {error && <div style={{ marginTop: 16 }}><ErrorBox message={error} onRetry={submit} /></div>}
      <button className="btn cta" type="button" style={{ marginTop: 26 }} disabled={submitting} onClick={submit}>
        {submitting ? '요청하는 중…' : '매칭 시작하기'}
      </button>
      <p className="screen-note">사람이 모이면 전용 채팅방이 열려요. 게임은 채팅에서 함께 정해요.</p>
    </>
  );
}

function MatchWaiting({ request, onChanged, onToast }) {
  const [canceling, setCanceling] = useState(false);
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
    <section className="match-card">
      <p className="match-lead">사람을 찾는 중</p>
      <h2>{partySizeLabel(request.minPlayers, request.maxPlayers)}</h2>
      <p className="screen-note">인원이 모이면 제안을 보내드려요.</p>
      <button className="btn white" type="button" style={{ marginTop: 22 }} disabled={canceling} onClick={cancel}>
        {canceling ? '취소하는 중…' : '매칭 취소'}
      </button>
    </section>
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

function MatchProposed({ proposal, onChanged, onToast }) {
  const remainingSeconds = Math.max(0, Math.ceil(useCountdown(proposal.respondBy) / 1000));
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
    <section className="match-card">
      <p className="match-lead">제안이 왔어요 · {remainingSeconds}초</p>
      <h2>{proposal.partySize}인 파티</h2>
      {/* 전원 수락 전에는 닉네임을 보여주지 않는다. 프로필 이미지만 익명으로 나열한다. */}
      <div className="match-avatars">
        {proposal.members.map((member, index) => (
          <Avatar key={index} imageUrl={member.profileImageUrl} name="" index={index} />
        ))}
      </div>
      {accepted && <p className="screen-note" style={{ marginTop: 18 }}>다른 인원을 기다리는 중이에요.</p>}
      <div className="btn-row" style={{ marginTop: 22 }}>
        <button className="btn cta" type="button" disabled={disabled} onClick={() => respond('ACCEPT')}>
          {accepted ? '수락함' : pending === 'ACCEPT' ? '수락하는 중…' : '수락'}
        </button>
      </div>
      <div className="btn-row" style={{ marginTop: 8 }}>
        <button className="btn fill" type="button" disabled={disabled} onClick={() => respond('REQUEUE')}>
          {pending === 'REQUEUE' ? '처리 중…' : '건너뛰고 재대기'}
        </button>
        <button className="btn fill" type="button" disabled={disabled} onClick={() => respond('CANCEL')}>
          {pending === 'CANCEL' ? '처리 중…' : '매칭 취소'}
        </button>
      </div>
    </section>
  );
}

function MatchPreparing() {
  return (
    <section className="match-card">
      <p className="match-lead">채팅 준비 중</p>
      <h2>곧 채팅방이 열려요</h2>
      <div className="match-seats searching" aria-hidden="true">
        <span className="match-seat"><i /><b /></span>
        <span className="match-seat"><i /><b /></span>
        <span className="match-seat"><i /><b /></span>
      </div>
      <p className="screen-note">채팅이 열리면 자동으로 들어가요. 채팅방은 연 뒤 24시간 동안만 열려 있어요.</p>
    </section>
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

  return (
    <div className="screen sub">
      <TopBar onBack={onBack} title="실시간 파티 매칭" />
      <div className="screen-body pad-bottom">
        <p className="screen-lead">원하는 인원을 등록하면 사람이 모였을 때 전용 채팅으로 연결해요.</p>
        {error && <div style={{ marginTop: 16 }}><ErrorBox message={error} onRetry={() => reload()} /></div>}
        {!error && loading && !data && <p className="screen-note">불러오는 중…</p>}
        {!error && !loading && !state && <MatchRequestForm onSubmitted={() => reload()} onToast={onToast} />}
        {!error && state === 'WAITING' && <MatchWaiting request={data.request} onChanged={() => reload()} onToast={onToast} />}
        {!error && state === 'PAUSED' && <MatchPaused request={data.request} onChanged={() => reload()} onToast={onToast} />}
        {!error && state === 'PROPOSED' && <MatchProposed proposal={data.proposal} onChanged={() => reload()} onToast={onToast} />}
        {!error && state === 'PREPARING' && <MatchPreparing />}
      </div>
    </div>
  );
}

// ---- MATCH 채팅 ----

const MATCH_CHAT_RECONNECT_LIMIT = 3;
const MATCH_CHAT_RECONNECT_DELAY_MS = 2000;
const MS_PER_HOUR = 60 * 60 * 1000;
const MS_PER_MINUTE = 60 * 1000;

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

function MatchRosterSheet({ partyId, members, onClose, onToast }) {
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
  const lastEventIdRef = useRef(null);
  const chatLogRef = useRef(null);

  useEffect(() => {
    if (!partyId) return undefined;
    let active = true;
    api.getMatchChatMessages(partyId)
      .then((page) => {
        if (!active) return;
        const latest = (page.messages || []).reduce((max, message) => Math.max(max, Number(message.messageId) || 0), 0);
        if (latest > 0) lastEventIdRef.current = latest;
        setMessages((current) => mergeMatchChatMessages(current, page.messages || []));
        setNextBeforeMessageId(page.nextBeforeMessageId ?? null);
        setHasNext(Boolean(page.hasNext));
      })
      .catch((cause) => { if (active) setError(messageForError(cause)); });
    return () => { active = false; };
  }, [partyId]);

  useEffect(() => {
    if (!partyId) return undefined;
    let active = true;
    let socket;
    let reconnectTimer;
    let reconnectAttempts = 0;

    const connect = () => {
      if (!active) return;
      let currentSocket;
      try {
        currentSocket = api.openMatchChatWebSocket(partyId, { afterMessageId: lastEventIdRef.current });
        socket = currentSocket;
      } catch {
        return;
      }
      currentSocket.onopen = () => { reconnectAttempts = 0; };
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
        if (!active || reconnectAttempts >= MATCH_CHAT_RECONNECT_LIMIT) return;
        reconnectAttempts += 1;
        reconnectTimer = setTimeout(connect, MATCH_CHAT_RECONNECT_DELAY_MS);
      };
    };

    connect();
    return () => {
      active = false;
      clearTimeout(reconnectTimer);
      socket?.close();
    };
  }, [partyId]);

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
        <TopBar onBack={onBack} title="매칭 채팅" />
        <div className="screen-body pad-bottom"><p className="screen-note">불러오는 중…</p></div>
      </div>
    );
  }
  if (error || !handoff) {
    return (
      <div className="screen sub">
        <TopBar onBack={onBack} title="매칭 채팅" />
        <div className="screen-body pad-bottom"><ErrorBox message={error || '채팅을 열 수 없어요.'} /></div>
      </div>
    );
  }

  const memberByRef = new Map(handoff.members.map((member, index) => [member.participantRef, { ...member, index }]));

  return (
    <div className="chat-screen">
      <div className="chat-topbar">
        <button type="button" className="icon-btn" aria-label="채팅방 나가기" onClick={leave}><BackIcon /></button>
        <div className="chat-topbar-copy">
          <strong>매칭 채팅</strong>
          <span>{formatRemainingClose(handoff.closesAt)}</span>
        </div>
        <button type="button" className="icon-btn" aria-label="참가자" onClick={() => setRosterOpen(true)}><InfoIcon /></button>
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
        <input id="match-chat-message" value={content} onChange={(event) => setContent(event.target.value)} disabled={sending} placeholder="메시지 입력" />
        <button className="chat-send" type="submit" disabled={sending || !content.trim()} aria-label="보내기"><SendIcon /></button>
      </form>
      {sendError && <div className="chat-fail" style={{ margin: '0 18px 14px' }} role="alert"><span>{sendError}</span></div>}

      {rosterOpen && (
        <MatchRosterSheet partyId={partyId} members={handoff.members} onClose={() => setRosterOpen(false)} onToast={onToast} />
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
