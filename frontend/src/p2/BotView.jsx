import React, { useState } from 'react';
import { SendIcon, TopBar } from '../shared/ui';
import { P2_NOTICE } from './MatchView';

/**
 * P2 시안. 담당자 명세와 봇 서버 API가 아직 없다.
 *
 * 배치의 핵심은 실행 전 확인 카드다. 봇은 서버를 직접 호출하지 않고 할 일을 요약해 보여 주며,
 * 사용자가 누르면 기존 화면이 기존 인증·CSRF 흐름 그대로 처리한다. 답을 만드는 것은 서버 몫이라
 * 여기서는 예시 대화만 그려 두고 조작에는 준비 중임을 알린다.
 */

const SUGGESTIONS = ['초보 환영 모임 찾아줘', '온라인으로 지금 할 사람', '윙스팬 모임 만들어줘'];

const PREVIEW_MESSAGES = [
  { bot: true, text: '어떤 모임을 찾아드릴까요? 인원, 시간, 난이도 중 하나만 말해도 괜찮아요.' },
  { text: '오늘 저녁 4인 가벼운 게임으로 모임 만들어줘' },
  { bot: true, text: '이 조건으로 모임을 열까요? 확인 후에만 실제로 만들어져요.' }
];

const PREVIEW_PLAN = {
  title: '모임 만들기',
  lines: ['게임 · 아줄', '일시 · 오늘 19:30', '장소 · 직접 채우기', '인원 · 호스트 포함 4명'],
  cta: '이 조건으로 만들기',
  secondary: '내가 직접 채우기'
};

export function BotView({ onBack, onToast }) {
  const [draft, setDraft] = useState('');
  const notice = () => onToast(P2_NOTICE);

  return (
    <div className="screen sub">
      <TopBar onBack={onBack} backLabel="알밤봇 닫기" action={(
        <span className="bot-title">
          <strong>알밤봇</strong>
          <span>물어보면 모임·게임을 찾아드려요</span>
        </span>
      )} />
      <div className="bot-log">
        {PREVIEW_MESSAGES.map((message, index) => (
          <p className={'bot-msg' + (message.bot ? '' : ' me')} key={index}>{message.text}</p>
        ))}
        <section className="bot-plan" aria-label="실행 전 확인">
          <p className="bot-plan-lead">새로 할 일</p>
          <h2>{PREVIEW_PLAN.title}</h2>
          <ul>
            {PREVIEW_PLAN.lines.map((line) => <li key={line}>{line}</li>)}
          </ul>
          <div className="btn-row">
            <button className="btn sm" type="button" onClick={notice}>{PREVIEW_PLAN.cta}</button>
            <button className="btn fill sm" type="button" onClick={notice}>{PREVIEW_PLAN.secondary}</button>
          </div>
          <p className="bot-plan-note">누를 때까지는 아무것도 실행되지 않아요.</p>
        </section>
      </div>
      <div className="bot-chips nos">
        {SUGGESTIONS.map((suggestion) => (
          <button className="chip" type="button" key={suggestion} onClick={notice}>{suggestion}</button>
        ))}
      </div>
      <form className="chat-compose" onSubmit={(event) => { event.preventDefault(); notice(); }}>
        <label className="sr-only" htmlFor="bot-message">알밤봇에게 묻기</label>
        <input
          id="bot-message"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          placeholder="예) 오늘 저녁 4인 가벼운 게임"
        />
        <button className="chat-send" type="submit" disabled={!draft.trim()} aria-label="보내기"><SendIcon /></button>
      </form>
    </div>
  );
}
