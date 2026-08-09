import React, { useState } from 'react';

const SECTION_ICONS = {
  rooms: <><circle cx="9" cy="8" r="3.2" /><path d="M2.5 20c0-3.6 2.9-5.8 6.5-5.8s6.5 2.2 6.5 5.8" /><path d="M16.5 5.6a3.2 3.2 0 0 1 0 6.2" /><path d="M18.5 14.6c2 .8 3 2.6 3 5.4" /></>,
  games: <><rect x="3" y="3" width="18" height="18" rx="4" /><circle cx="8.5" cy="8.5" r="1.1" /><circle cx="15.5" cy="8.5" r="1.1" /><circle cx="8.5" cy="15.5" r="1.1" /><circle cx="15.5" cy="15.5" r="1.1" /></>,
  list: <><path d="M8 6h13" /><path d="M8 12h13" /><path d="M8 18h13" /><path d="M3.5 6h.01" /><path d="M3.5 12h.01" /><path d="M3.5 18h.01" /></>,
  calendar: <><rect x="3" y="5" width="18" height="16" rx="3" /><path d="M8 3v4" /><path d="M16 3v4" /><path d="M3 10h18" /></>,
  pencil: <><path d="M12 20h9" /><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z" /></>,
  chat: <path d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z" />
};

export function SectionIcon({ name }) {
  return (
    <svg className="h2-ico" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{SECTION_ICONS[name]}</svg>
  );
}

// 게임 찾기·모임 찾기가 공유하는 검색 헤더. 검색창·액션을 조건 필터 바 안에 넣어 한 줄에 둔다.
export function SearchHeader({ icon, title, keywordId, keywordLabel, inputValue, onInputChange, onSubmit, placeholder, hint, actionSlot, filtersSlot }) {
  const searchForm = (
    <form className="inline-search" onSubmit={onSubmit}>
      <label className="sr-only" htmlFor={keywordId}>{keywordLabel}</label>
      <input id={keywordId} value={inputValue} onChange={onInputChange} placeholder={placeholder} />
      <button type="submit" aria-label="검색"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5" /><line x1="21.5" y1="21.5" x2="15.3" y2="15.3" /></svg></button>
    </form>
  );
  const hintNode = hint ? <p className="hint search-header-hint">{hint}</p> : null;

  return (
    <>
      <h2><SectionIcon name={icon} />{title}</h2>
      {hintNode}
      {filtersSlot(<>{searchForm}{actionSlot}</>)}
    </>
  );
}

export function LoadingBox({ label = '불러오는 중…' }) {
  return <div className="infobox">{label}</div>;
}

export function ErrorBox({ message }) {
  return <div className="infobox red">{message}</div>;
}

export function Pagination({ page, totalPages, loading, onChange }) {
  if (!totalPages || totalPages <= 1) return null;
  const windowSize = 5;
  const start = Math.max(0, Math.min(page - Math.floor(windowSize / 2), totalPages - windowSize));
  const end = Math.min(totalPages, start + windowSize);
  const numbers = [];
  for (let index = start; index < end; index += 1) numbers.push(index);
  const go = (next) => { if (next >= 0 && next < totalPages && next !== page) onChange(next); };
  return (
    <nav className="pagination" aria-label="페이지 이동">
      <button className="page-btn" type="button" disabled={loading || page <= 0} onClick={() => go(page - 1)} aria-label="이전 페이지">‹</button>
      {start > 0 && <><button className="page-btn" type="button" disabled={loading} onClick={() => go(0)}>1</button>{start > 1 && <span className="page-ellipsis">…</span>}</>}
      {numbers.map((index) => (
        <button key={index} className={'page-btn' + (index === page ? ' on' : '')} type="button" disabled={loading} aria-current={index === page ? 'page' : undefined} onClick={() => go(index)}>{index + 1}</button>
      ))}
      {end < totalPages && <>{end < totalPages - 1 && <span className="page-ellipsis">…</span>}<button className="page-btn" type="button" disabled={loading} onClick={() => go(totalPages - 1)}>{totalPages}</button></>}
      <button className="page-btn" type="button" disabled={loading || page >= totalPages - 1} onClick={() => go(page + 1)} aria-label="다음 페이지">›</button>
    </nav>
  );
}
