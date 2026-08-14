import React, { useEffect, useRef, useState } from 'react';
import { api } from '../api';
import { normalizeGameSummary } from '../game';
import { SendIcon, TopBar } from '../shared/ui';

/**
 * P2 시안. 담당자 명세가 확정되기 전의 배치 제안이라 봇 전용 서버 API는 없다.
 *
 * 봇은 서버를 직접 호출하지 않는다. 낱말을 보고 할 일을 요약한 확인 카드를 띄우고,
 * 사용자가 누르면 기존 화면으로 값을 넘길 뿐이다. 실제 생성·조회는 그 화면이 기존
 * 인증·CSRF 흐름 그대로 수행한다. 게임 이름을 찾을 때만 공개 조회 API를 읽는다.
 */

const SUGGESTIONS = ['초보 환영 모임 찾아줘', '온라인으로 지금 할 사람', '윙스팬 모임 만들어줘'];

const GREETING = '어떤 모임을 찾아드릴까요? 인원, 시간, 난이도 중 하나만 말해도 괜찮아요.';

const REPLY_DELAY_MS = 500;

// 확인 카드로 이어지는 말버릇만 추린다. 시안이라 형태소 분석은 하지 않는다.
const INTENTS = [
  {
    kind: 'match',
    pattern: /온라인|아레나|지금|바로/,
    reply: '보드게임아레나에서 바로 시작할 수 있는 매칭을 찾아볼게요. 게임과 인원만 확인해주세요.',
    title: '온라인 자동 매칭',
    lines: ['연결 · 보드게임아레나', '인원 · 매칭 화면에서 고르기'],
    cta: '매칭 화면 열기'
  },
  {
    kind: 'create',
    pattern: /만들|열어|개설|호스트/,
    reply: '이 조건으로 모임을 열까요? 확인 후에만 실제로 만들어져요.',
    title: '모임 만들기',
    cta: '이 조건으로 만들기',
    secondary: '내가 직접 채우기'
  },
  {
    kind: 'rooms',
    pattern: /초보|처음|쉬운|룰마스터|찾아/,
    reply: '조건에 맞는 모임을 모임 찾기에서 보여드릴게요.',
    title: '모임 찾기',
    lines: ['조건 · 모집 중인 모임', '정렬 · 시작 시간 순'],
    cta: '모임 찾기에서 보기'
  }
];

const IGNORED_WORDS = /^(모임|게임|찾아줘|찾아|만들어줘|만들어|열어줘|열어|해줘|하고|싶어|같이|사람|추천)$/;

/** 문장에서 게임 이름일 만한 낱말을 골라 공개 게임 조회로 확인한다. */
async function findGame(text, signal) {
  const words = text
    .split(/\s+/)
    .map((word) => word.replace(/[.,!?]/g, ''))
    .filter((word) => word.length >= 2 && !IGNORED_WORDS.test(word))
    .sort((left, right) => right.length - left.length)
    .slice(0, 2);
  for (const word of words) {
    const found = await api.getGames({ keyword: word, page: 0, size: 1 }, signal);
    const game = (found.content || [])[0];
    if (game) return normalizeGameSummary(game);
  }
  return null;
}

async function planFor(text, signal) {
  const intent = INTENTS.find((candidate) => candidate.pattern.test(text));
  if (!intent) {
    return { reply: '조건을 조금 더 알려주세요. 인원, 시간, 난이도 중 하나만이라도 괜찮아요.', plan: null };
  }
  if (intent.kind !== 'create') return { reply: intent.reply, plan: intent };
  const game = await findGame(text, signal);
  return {
    reply: intent.reply,
    plan: {
      ...intent,
      game,
      lines: [
        game ? '게임 · ' + game.title : '게임 · 만들기 화면에서 고르기',
        '나머지 · 제목과 장소는 직접 채우기'
      ]
    }
  };
}

export function BotView({ onBack, onCreateGame, onNavigate }) {
  const [messages, setMessages] = useState([{ bot: true, text: GREETING }]);
  const [draft, setDraft] = useState('');
  const [typing, setTyping] = useState(false);
  const [plan, setPlan] = useState(null);
  const logRef = useRef(null);
  const replyRef = useRef(0);

  useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight;
  }, [messages, typing, plan]);

  useEffect(() => () => window.clearTimeout(replyRef.current), []);

  const send = async (text) => {
    const asked = text.trim();
    if (!asked || typing) return;
    setMessages((current) => current.concat([{ text: asked }]));
    setDraft('');
    setPlan(null);
    setTyping(true);
    let answer;
    try {
      answer = await planFor(asked, undefined);
    } catch {
      answer = { reply: '지금은 답을 만들지 못했어요. 잠시 뒤에 다시 물어봐주세요.', plan: null };
    }
    // 사람이 읽을 틈을 두려고 답을 조금 늦춘다.
    replyRef.current = window.setTimeout(() => {
      setTyping(false);
      setMessages((current) => current.concat([{ bot: true, text: answer.reply }]));
      setPlan(answer.plan);
    }, REPLY_DELAY_MS);
  };

  const confirm = () => {
    const confirmed = plan;
    setPlan(null);
    if (confirmed.kind === 'create') {
      if (confirmed.game) onCreateGame(confirmed.game);
      else onNavigate('/create');
      return;
    }
    onNavigate(confirmed.kind === 'match' ? '/match' : '/find');
  };

  return (
    <div className="screen sub">
      <TopBar onBack={onBack} backLabel="알밤봇 닫기" action={(
        <span className="bot-title">
          <strong>알밤봇</strong>
          <span>물어보면 모임·게임을 찾아드려요</span>
        </span>
      )} />
      <div className="bot-log" ref={logRef}>
        {messages.map((message, index) => (
          <p className={'bot-msg' + (message.bot ? '' : ' me')} key={index}>{message.text}</p>
        ))}
        {typing && <p className="bot-msg typing" role="status">생각하고 있어요…</p>}
        {plan && (
          <section className="bot-plan" aria-label="실행 전 확인">
            <p className="bot-plan-lead">새로 할 일</p>
            <h2>{plan.title}</h2>
            <ul>
              {plan.lines.map((line) => <li key={line}>{line}</li>)}
            </ul>
            <div className="btn-row">
              <button className="btn sm" type="button" onClick={confirm}>{plan.cta}</button>
              {plan.secondary && (
                <button className="btn fill sm" type="button" onClick={() => { setPlan(null); onNavigate('/create'); }}>{plan.secondary}</button>
              )}
            </div>
            <p className="bot-plan-note">누를 때까지는 아무것도 실행되지 않아요.</p>
          </section>
        )}
      </div>
      <div className="bot-chips nos">
        {SUGGESTIONS.map((suggestion) => (
          <button className="chip" type="button" key={suggestion} onClick={() => send(suggestion)}>{suggestion}</button>
        ))}
      </div>
      <form
        className="chat-compose"
        onSubmit={(event) => { event.preventDefault(); send(draft); }}
      >
        <label className="sr-only" htmlFor="bot-message">알밤봇에게 묻기</label>
        <input
          id="bot-message"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          placeholder="예) 오늘 저녁 4인 가벼운 게임"
        />
        <button className="chat-send" type="submit" disabled={!draft.trim() || typing} aria-label="보내기"><SendIcon /></button>
      </form>
    </div>
  );
}
