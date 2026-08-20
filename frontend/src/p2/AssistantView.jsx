import React, { useEffect, useRef, useState } from 'react';
import { ApiError, api } from '../api';
import { ErrorBox, SendIcon, TopBar } from '../shared/ui';

const REGIONS = ['홍대', '강남', '건대', '잠실'];
const EXPERIENCE_LABELS = {
  ALL_LEVELS: '경험 무관',
  BEGINNER_WELCOME: '초보 환영',
  EXPERIENCED_PREFERRED: '경험자 위주'
};
const MISSING_FIELD_LABELS = {
  GAME_STYLE: '어떤 분위기나 게임 스타일을 원하는지 알려주세요.'
};
const QUICK_PROMPTS = ['초보 환영 모임 찾아줘', '오늘 저녁 4인 가볍게 할 게임 추천해줘', '윙스팬 모임 만들어줘'];

function botReplyText(result) {
  if (!result) return '';
  if (result.state === 'NEEDS_INPUT') {
    return (result.missingFields || []).map((field) => MISSING_FIELD_LABELS[field] || '필요한 조건을 더 알려주세요.').join(' ') || '조금만 더 알려주세요.';
  }
  if (result.state === 'NO_CANDIDATES') return '맞는 게임을 찾지 못했어요. 인원, 테마, 난이도 중 하나를 바꿔 다시 물어보세요.';
  if (result.state === 'UNSUPPORTED') return '도와드릴 수 있는 요청이 아니에요. 게임 추천이나 모임 만들기 조건을 알려주시면 도와드릴게요.';
  if (result.state === 'RECOMMENDED') return '이 조건으로 찾아봤어요. 이 게임들은 어때요?';
  return '';
}

function isGranted(consent) {
  return consent?.status === 'GRANTED';
}

function isExpiredDraftError(error) {
  return error instanceof ApiError
    && (error.status === 410 || error.code === 'ASSISTANT_DRAFT_EXPIRED');
}

function isConsentRequiredError(error) {
  return error instanceof ApiError && error.code === 'ASSISTANT_CONSENT_REQUIRED';
}

function assistantErrorMessage(error) {
  if (error instanceof ApiError) {
    if (error.status === 401 || error.code === 'UNAUTHENTICATED') return '로그인이 만료됐어요. 다시 로그인한 뒤 이어서 해주세요.';
    if (isConsentRequiredError(error)) return 'AI 추천을 사용하려면 AI 처리 동의가 필요해요. 동의 상태를 다시 확인해주세요.';
    if (error.status === 403 || error.code === 'CSRF_TOKEN_INVALID') return '보안 확인이 만료됐어요. 새로고침한 뒤 다시 시도해주세요.';
    if (error.status === 410 || error.code === 'ASSISTANT_DRAFT_EXPIRED') return '초안이 만료됐어요. 새 모임 만들기부터 다시 시작해주세요.';
    if (error.status === 409 || error.code?.endsWith('_CONFLICT') || error.code === 'ASSISTANT_CONSENT_VERSION_MISMATCH') {
      return '다른 요청으로 초안 상태가 바뀌었어요. 최신 상태를 확인한 뒤 다시 시도해주세요.';
    }
    if (error.status === 429 || error.code === 'RATE_LIMIT_EXCEEDED' || error.code === 'ASSISTANT_COST_LIMIT_EXCEEDED') {
      return '현재 AI 사용 한도에 도달했어요. 잠시 뒤 다시 시도해주세요.';
    }
    if (error.status === 503 || error.code === 'ASSISTANT_NOT_ENABLED' || error.code === 'ASSISTANT_PROVIDER_UNAVAILABLE'
      || error.code === 'ASSISTANT_PROVIDER_RESPONSE_INVALID' || error.code === 'SERVICE_UNAVAILABLE') {
      return '지금은 AI 도우미를 사용할 수 없어요. 잠시 뒤 다시 시도하거나 직접 모임을 만들어주세요.';
    }
  }
  return '요청을 처리하지 못했어요. 같은 내용을 다시 입력하거나 잠시 뒤 시도해주세요.';
}

function localDateTimeValue(value) {
  if (!value) return '';
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})/.exec(value);
  return match ? match[1] : '';
}

function toSeoulInstant(value) {
  return value ? value + ':00+09:00' : null;
}

function formatStartsAt(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '일시 미정';
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    month: 'long',
    day: 'numeric',
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(date);
}

function newIdempotencyKey() {
  return globalThis.crypto?.randomUUID?.() || 'assistant-' + Date.now() + '-' + Math.random().toString(36).slice(2);
}

function ConsentCard({ consent, pending, onGrant }) {
  return (
    <section className="assistant-card assistant-consent" aria-labelledby="assistant-consent-title">
      <p className="assistant-eyebrow">처음 AI 도우미를 쓰기 전에</p>
      <h2 id="assistant-consent-title">AI 사용 동의</h2>
      <p>입력한 자연어는 모임 추천을 위해 OpenAI에 전달될 수 있어요. 일반 모임·채팅 기능은 동의하지 않아도 그대로 이용할 수 있어요.</p>
      <dl className="assistant-policy-summary">
        <div><dt>처리 제공자</dt><dd>OpenAI</dd></div>
        <div><dt>저장 설정</dt><dd>{consent.store ? '저장 가능' : '저장하지 않음'}</dd></div>
        <div><dt>정책 버전</dt><dd>{consent.policyVersion}</dd></div>
      </dl>
      <a className="assistant-policy-link" href={consent.policyUrl} target="_blank" rel="noreferrer noopener">provider 정책 보기</a>
      <button className="btn" type="button" disabled={pending} onClick={onGrant}>{pending ? '동의 처리 중…' : '동의하고 시작하기'}</button>
    </section>
  );
}

function RecommendationResult({ result, selectedCandidate, onSelectCandidate }) {
  if (!result || result.state !== 'RECOMMENDED') return null;
  return (
    <section className="assistant-card" aria-labelledby="assistant-recommendations-title">
      <p className="assistant-eyebrow">서버 조건으로 찾은 후보</p>
      <h2 id="assistant-recommendations-title">이 게임들은 어때요?</h2>
      <div className="assistant-candidates">
        {(result.candidates || []).map((candidate) => {
          const selected = selectedCandidate?.id === candidate.id;
          return (
            <button
              className={'assistant-candidate' + (selected ? ' on' : '')}
              type="button"
              key={candidate.id}
              aria-pressed={selected}
              onClick={() => onSelectCandidate(candidate)}
            >
              <strong>{candidate.name || candidate.title}</strong>
              <span>이 게임으로 모임 만들기</span>
            </button>
          );
        })}
      </div>
    </section>
  );
}

function DraftCreationForm({ candidate, conditions, onCreate }) {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [startsAt, setStartsAt] = useState(localDateTimeValue(conditions?.startsAt));
  const [region, setRegion] = useState(conditions?.region || '홍대');
  const [playerCount, setPlayerCount] = useState(String(conditions?.playerCount || 4));
  const [experienceLevel, setExperienceLevel] = useState(conditions?.experienceLevel || 'BEGINNER_WELCOME');
  const [isRulemasterLed, setIsRulemasterLed] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const submit = async (event) => {
    event.preventDefault();
    if (!title.trim() || !startsAt) {
      setError('모임 제목과 시작 시각을 입력해주세요.');
      return;
    }
    setSaving(true);
    setError('');
    try {
      await onCreate({
        roomType: 'GAME_FOCUSED',
        title: title.trim(),
        description: description.trim() || null,
        gameId: candidate.id,
        experienceLevel,
        isRulemasterLed,
        startsAt: toSeoulInstant(startsAt),
        region,
        place: null,
        recruitmentCapacity: Number(playerCount) - 1
      });
    } catch (requestError) {
      setError(assistantErrorMessage(requestError));
    } finally {
      setSaving(false);
    }
  };

  return (
    <form className="assistant-card assistant-draft-form" onSubmit={submit} aria-label="AI 초안 만들기">
      <p className="assistant-eyebrow">확인 전에는 모임이 만들어지지 않아요</p>
      <h2>{candidate.name || candidate.title} 모임 정보</h2>
      <div className="field">
        <label className="field-label" htmlFor="assistant-draft-title">모임 제목</label>
        <input id="assistant-draft-title" className="field-input" value={title} maxLength="100" onChange={(event) => setTitle(event.target.value)} />
      </div>
      <div className="field">
        <label className="field-label" htmlFor="assistant-draft-starts-at">시작 시각</label>
        <input id="assistant-draft-starts-at" className="field-input" type="datetime-local" value={startsAt} onChange={(event) => setStartsAt(event.target.value)} />
      </div>
      <div className="assistant-form-grid">
        <div className="field">
          <label className="field-label" htmlFor="assistant-draft-region">지역</label>
          <select id="assistant-draft-region" className="field-input" value={region} onChange={(event) => setRegion(event.target.value)}>
            {REGIONS.map((value) => <option value={value} key={value}>{value}</option>)}
          </select>
        </div>
        <div className="field">
          <label className="field-label" htmlFor="assistant-draft-player-count">총 인원</label>
          <select id="assistant-draft-player-count" className="field-input" value={playerCount} onChange={(event) => setPlayerCount(event.target.value)}>
            {Array.from({ length: 10 }, (_, index) => index + 2).map((value) => <option value={value} key={value}>{value}명</option>)}
          </select>
        </div>
      </div>
      <div className="field">
        <label className="field-label" htmlFor="assistant-draft-experience">참가 경험</label>
        <select id="assistant-draft-experience" className="field-input" value={experienceLevel} onChange={(event) => setExperienceLevel(event.target.value)}>
          {Object.entries(EXPERIENCE_LABELS).map(([value, label]) => <option value={value} key={value}>{label}</option>)}
        </select>
      </div>
      <div className="field">
        <label className="field-label" htmlFor="assistant-draft-description">소개 (선택)</label>
        <textarea id="assistant-draft-description" className="field-input" value={description} maxLength="255" onChange={(event) => setDescription(event.target.value)} />
      </div>
      <label className="assistant-checkbox"><input type="checkbox" checked={isRulemasterLed} onChange={(event) => setIsRulemasterLed(event.target.checked)} /> 룰 설명을 진행할게요</label>
      {error && <p className="field-hint warn" role="alert">{error}</p>}
      <button className="btn" type="submit" disabled={saving}>{saving ? '초안을 만드는 중…' : '확인 카드 만들기'}</button>
    </form>
  );
}

function DraftCard({ draft, onSave, onDiscard, onConfirm }) {
  const [place, setPlace] = useState(draft.input.place || '');
  const [region, setRegion] = useState(draft.input.region || '홍대');
  const [saving, setSaving] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [discarding, setDiscarding] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    setPlace(draft.input.place || '');
    setRegion(draft.input.region || '홍대');
    setError('');
  }, [draft]);

  const hasChanges = place.trim() !== (draft.input.place || '') || region !== (draft.input.region || '홍대');

  const save = async () => {
    if (!place.trim()) {
      setError('상세 장소를 입력해주세요.');
      return;
    }
    setSaving(true);
    setError('');
    try {
      await onSave({ place: place.trim(), region });
    } catch (requestError) {
      setError(assistantErrorMessage(requestError));
    } finally {
      setSaving(false);
    }
  };

  const confirm = async () => {
    if (!place.trim()) {
      setError('상세 장소를 입력한 뒤 확인해주세요.');
      return;
    }
    if (hasChanges) {
      setError('장소나 지역을 바꿨다면 먼저 변경 내용을 저장해주세요.');
      return;
    }
    setConfirming(true);
    setError('');
    try {
      await onConfirm();
    } catch (requestError) {
      setError(assistantErrorMessage(requestError));
    } finally {
      setConfirming(false);
    }
  };

  const discard = async () => {
    setDiscarding(true);
    setError('');
    try {
      await onDiscard();
    } catch (requestError) {
      setError(assistantErrorMessage(requestError));
      setDiscarding(false);
    }
  };

  return (
    <section className="assistant-card assistant-confirmation" aria-labelledby="assistant-draft-title">
      <p className="assistant-eyebrow">15분 동안 확인할 수 있는 초안</p>
      <h2 id="assistant-draft-title">{draft.input.title}</h2>
      <dl className="assistant-draft-summary">
        <div><dt>시작</dt><dd>{formatStartsAt(draft.input.startsAt)}</dd></div>
        <div><dt>인원</dt><dd>총 {Number(draft.input.recruitmentCapacity) + 1}명</dd></div>
        <div><dt>참가 경험</dt><dd>{EXPERIENCE_LABELS[draft.input.experienceLevel] || draft.input.experienceLevel}</dd></div>
      </dl>
      <div className="assistant-form-grid">
        <div className="field">
          <label className="field-label" htmlFor="assistant-confirm-region">지역</label>
          <select id="assistant-confirm-region" className="field-input" value={region} disabled={saving || confirming} onChange={(event) => setRegion(event.target.value)}>
            {REGIONS.map((value) => <option value={value} key={value}>{value}</option>)}
          </select>
        </div>
        <div className="field">
          <label className="field-label" htmlFor="assistant-confirm-place">상세 장소</label>
          <input id="assistant-confirm-place" className="field-input" value={place} maxLength="100" placeholder="예) 홍대 보드게임 카페" disabled={saving || confirming} onChange={(event) => setPlace(event.target.value)} />
        </div>
      </div>
      {error && <p className="field-hint warn" role="alert">{error}</p>}
      <div className="btn-row">
        <button className="btn fill" type="button" disabled={!hasChanges || saving || confirming} onClick={save}>{saving ? '저장 중…' : '변경 저장'}</button>
        <button className="btn" type="button" disabled={!place.trim() || hasChanges || saving || confirming} onClick={confirm}>{confirming ? '확인 중…' : '방 만들기 확정'}</button>
      </div>
      <button className="assistant-discard" type="button" disabled={saving || confirming || discarding} onClick={discard}>{discarding ? '초안을 버리는 중…' : '초안 버리기'}</button>
      <p className="assistant-note">확정 전에는 Room이나 채팅방이 만들어지지 않아요.</p>
    </section>
  );
}

function AssistantStart({ onCreateDraft, onConsentRequired }) {
  const [message, setMessage] = useState('');
  const [history, setHistory] = useState([]);
  const [result, setResult] = useState(null);
  const [selectedCandidate, setSelectedCandidate] = useState(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const logRef = useRef(null);

  useEffect(() => {
    const log = logRef.current;
    if (log) log.scrollTop = log.scrollHeight;
  }, [history, result, selectedCandidate, error, pending]);

  const send = async (text) => {
    const currentMessage = text.trim();
    if (!currentMessage || pending) return;
    setHistory((entries) => [...entries, { role: 'mine', text: currentMessage }]);
    setMessage('');
    setPending(true);
    setError('');
    try {
      const next = await api.recommendAssistant(currentMessage, result?.conditions || null);
      setResult(next);
      setSelectedCandidate(null);
      const reply = botReplyText(next);
      if (reply) setHistory((entries) => [...entries, { role: 'theirs', text: reply }]);
    } catch (requestError) {
      if (isConsentRequiredError(requestError)) onConsentRequired();
      else setError(assistantErrorMessage(requestError));
    } finally {
      setPending(false);
    }
  };

  const submit = (event) => {
    event.preventDefault();
    send(message);
  };

  return (
    <>
      <div className="chat-log assistant-log" ref={logRef}>
        <div className="chat-message theirs">
          <span className="chat-line"><span className="chat-content">같이 할 게임을 찾아볼까요? 인원, 시간, 게임 분위기 중 아는 것부터 편하게 알려주세요.</span></span>
        </div>
        {history.map((turn, index) => (
          <div className={'chat-message ' + turn.role} key={index}>
            <span className="chat-line"><span className="chat-content">{turn.text}</span></span>
          </div>
        ))}
        {pending && (
          <div className="chat-message theirs" aria-hidden="true">
            <span className="chat-line"><span className="chat-content">찾아보는 중…</span></span>
          </div>
        )}
        <RecommendationResult result={result} selectedCandidate={selectedCandidate} onSelectCandidate={setSelectedCandidate} />
        {selectedCandidate && <DraftCreationForm key={selectedCandidate.id} candidate={selectedCandidate} conditions={result?.conditions} onCreate={onCreateDraft} />}
        {error && <p className="assistant-error" role="alert">{error}</p>}
      </div>
      <div className="chiprow assistant-suggestions">
        {QUICK_PROMPTS.map((prompt) => (
          <button className="chip" type="button" key={prompt} disabled={pending} onClick={() => send(prompt)}>{prompt}</button>
        ))}
      </div>
      <form className="chat-compose" onSubmit={submit}>
        <label className="sr-only" htmlFor="assistant-message">알밤봇에게 묻기</label>
        <textarea id="assistant-message" value={message} maxLength="2000" placeholder="예) 초보자와 주말 저녁에 할 협력 게임을 추천해줘" disabled={pending} onChange={(event) => setMessage(event.target.value)} />
        <button className="chat-send" type="submit" disabled={!message.trim() || pending} aria-label={pending ? '전송 중…' : '전송'}>
          <SendIcon />
        </button>
      </form>
    </>
  );
}

export function AssistantView({ onBack, onNavigate }) {
  const [consent, setConsent] = useState({ loading: true, data: null, error: '' });
  const [draftState, setDraftState] = useState({ loading: true, draft: null, expired: false, error: '' });
  const [reloadVersion, setReloadVersion] = useState(0);
  const [granting, setGranting] = useState(false);
  const confirmKeys = useRef(new Map());
  const retryBootstrap = () => setReloadVersion((version) => version + 1);
  const recoverConsentRequired = (error) => {
    if (isConsentRequiredError(error)) retryBootstrap();
    throw error;
  };
  const recoverActiveDraftError = (error) => {
    if (isExpiredDraftError(error)) {
      confirmKeys.current.clear();
      setDraftState({ loading: false, draft: null, expired: true, error: '' });
    } else if (isConsentRequiredError(error)) {
      retryBootstrap();
    }
    throw error;
  };

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    setConsent((current) => ({ ...current, loading: true, error: '' }));
    setDraftState((current) => ({ ...current, loading: true, error: '' }));

    api.getAssistantConsent(controller.signal)
      .then((data) => { if (active) setConsent({ loading: false, data, error: '' }); })
      .catch((error) => { if (active && error?.name !== 'AbortError') setConsent({ loading: false, data: null, error: assistantErrorMessage(error) }); });
    api.getActiveAssistantDraft(controller.signal)
      .then((draft) => { if (active) setDraftState({ loading: false, draft, expired: false, error: '' }); })
      .catch((error) => {
        if (!active || error?.name === 'AbortError') return;
        if (isExpiredDraftError(error)) setDraftState({ loading: false, draft: null, expired: true, error: '' });
        else setDraftState({ loading: false, draft: null, expired: false, error: assistantErrorMessage(error) });
      });
    return () => {
      active = false;
      controller.abort();
    };
  }, [reloadVersion]);

  const grant = async () => {
    setGranting(true);
    try {
      const updated = await api.changeAssistantConsent({
        decision: 'GRANT',
        consentVersion: consent.data.consentVersion
      });
      setConsent({ loading: false, data: updated, error: '' });
    } catch (error) {
      setConsent((current) => ({ ...current, error: assistantErrorMessage(error) }));
    } finally {
      setGranting(false);
    }
  };

  const createDraft = async (input) => {
    try {
      const draft = await api.createAssistantDraft(input);
      confirmKeys.current.clear();
      setDraftState({ loading: false, draft, expired: false, error: '' });
    } catch (error) {
      recoverConsentRequired(error);
    }
  };

  const saveDraft = async (changes) => {
    try {
      const draft = draftState.draft;
      const updated = await api.updateAssistantDraft(draft.draftId, { draftVersion: draft.draftVersion, ...changes });
      confirmKeys.current.clear();
      setDraftState({ loading: false, draft: updated, expired: false, error: '' });
      return updated;
    } catch (error) {
      recoverActiveDraftError(error);
    }
  };

  const discardDraft = async () => {
    try {
      await api.discardAssistantDraft(draftState.draft.draftId);
      confirmKeys.current.clear();
      setDraftState({ loading: false, draft: null, expired: false, error: '' });
    } catch (error) {
      recoverActiveDraftError(error);
    }
  };

  const confirmDraft = async () => {
    try {
      const draft = draftState.draft;
      const scope = draft.draftId + ':' + draft.draftVersion;
      const key = confirmKeys.current.get(scope) || newIdempotencyKey();
      confirmKeys.current.set(scope, key);
      const result = await api.confirmAssistantDraft(draft.draftId, draft.draftVersion, key);
      onNavigate('/session/' + result.roomId, { replace: true });
    } catch (error) {
      recoverActiveDraftError(error);
    }
  };

  const startFresh = () => setDraftState({ loading: false, draft: null, expired: false, error: '' });

  let content;
  let chatMode = false;
  if (draftState.loading) {
    content = <p className="assistant-loading" role="status">확인 카드를 불러오는 중이에요.</p>;
  } else if (draftState.draft) {
    chatMode = true;
    content = (
      <div className="chat-log assistant-log">
        <div className="chat-message theirs">
          <span className="chat-line"><span className="chat-content">이 조건으로 모임을 열까요? 확인 후에만 실제로 만들어져요.</span></span>
        </div>
        <DraftCard draft={draftState.draft} onSave={saveDraft} onDiscard={discardDraft} onConfirm={confirmDraft} />
      </div>
    );
  } else if (draftState.expired) {
    content = (
      <section className="assistant-card" aria-live="polite">
        <h2>초안이 만료됐어요</h2>
        <p>초안은 생성 뒤 15분 동안만 확인할 수 있어요. 조건을 다시 입력해 새 확인 카드를 만들어주세요.</p>
        <button className="btn" type="button" onClick={startFresh}>새로 시작하기</button>
      </section>
    );
  } else if (draftState.error) {
    content = <ErrorBox title="확인 카드를 불러오지 못했어요" message={draftState.error} onRetry={retryBootstrap} />;
  } else if (consent.loading) {
    content = <p className="assistant-loading" role="status">AI 사용 상태를 확인하는 중이에요.</p>;
  } else if (consent.data && !isGranted(consent.data)) {
    chatMode = true;
    content = (
      <div className="chat-log assistant-log">
        <div className="chat-message theirs">
          <span className="chat-line"><span className="chat-content">처음이시네요! 시작 전에 AI 사용 동의가 필요해요.</span></span>
        </div>
        <ConsentCard consent={consent.data} pending={granting} onGrant={grant} />
        {consent.error && <p className="assistant-error" role="alert">{consent.error}</p>}
      </div>
    );
  } else if (consent.error) {
    content = <ErrorBox title="AI 사용 상태를 불러오지 못했어요" message={consent.error} onRetry={retryBootstrap} />;
  } else {
    chatMode = true;
    content = <AssistantStart onCreateDraft={createDraft} onConsentRequired={retryBootstrap} />;
  }

  return (
    <div className="screen sub assistant-screen">
      <TopBar onBack={onBack} title="알밤봇" />
      <div className={'screen-body' + (chatMode ? ' chat-mode' : ' pad-bottom')}>{content}</div>
    </div>
  );
}

function consentStatusLabel(status) {
  if (status === 'GRANTED') return '동의함';
  if (status === 'REVOKED') return '철회됨';
  return '동의 안 함';
}

export function AssistantSettingsView({ onBack }) {
  const [state, setState] = useState({ loading: true, data: null, error: '' });
  const [revoking, setRevoking] = useState(false);
  const [notice, setNotice] = useState('');

  const load = () => {
    setState((current) => ({ ...current, loading: true, error: '' }));
    api.getAssistantConsent()
      .then((data) => setState({ loading: false, data, error: '' }))
      .catch((error) => setState({ loading: false, data: null, error: assistantErrorMessage(error) }));
  };

  useEffect(() => { load(); }, []);

  const revoke = async () => {
    setRevoking(true);
    setNotice('');
    try {
      const updated = await api.changeAssistantConsent({ decision: 'REVOKE' });
      setState({ loading: false, data: updated, error: '' });
      setNotice('AI 처리 동의를 철회했어요. 일반 서비스는 계속 이용할 수 있어요.');
    } catch (error) {
      setState((current) => ({ ...current, error: assistantErrorMessage(error) }));
    } finally {
      setRevoking(false);
    }
  };

  let content;
  if (state.loading) content = <p className="assistant-loading" role="status">AI 설정을 불러오는 중이에요.</p>;
  else if (state.error) content = <ErrorBox title="AI 설정을 불러오지 못했어요" message={state.error} onRetry={load} />;
  else {
    const consent = state.data;
    content = (
      <section className="assistant-card assistant-settings">
        <p className="assistant-eyebrow">외부 AI 처리</p>
        <h2>AI 설정</h2>
        <dl className="assistant-policy-summary">
          <div><dt>동의 상태</dt><dd>{consentStatusLabel(consent.status)}</dd></div>
          <div><dt>처리 제공자</dt><dd>{consent.provider === 'OPENAI' ? 'OpenAI' : consent.provider}</dd></div>
          <div><dt>정책 버전</dt><dd>{consent.policyVersion}</dd></div>
          <div><dt>저장 설정</dt><dd>{consent.store ? '저장 가능' : '저장하지 않음'}</dd></div>
        </dl>
        <a className="assistant-policy-link" href={consent.policyUrl} target="_blank" rel="noreferrer noopener">provider 정책 보기</a>
        {isGranted(consent) && <button className="btn fill" type="button" disabled={revoking} onClick={revoke}>{revoking ? '철회 중…' : 'AI 처리 동의 철회'}</button>}
        {!isGranted(consent) && <a className="btn" href="#/assistant">동의하고 AI 사용하기</a>}
        {notice && <p className="assistant-note" role="status">{notice}</p>}
      </section>
    );
  }
  return <div className="screen sub assistant-screen"><TopBar onBack={onBack} title="AI 설정" /><div className="screen-body pad-bottom">{content}</div></div>;
}
