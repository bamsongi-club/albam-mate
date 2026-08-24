import React from 'react';
import { playerColor, playerTextColor } from './players';
import defaultGameCover from '../../assets/default-game-cover.jpg';

// 로고는 인라인 SVG 단색 마크 한 벌만 쓴다. 로고 전용 서체는 쓰지 않는다.
export function BrandMark({ size = 32, tone = '#0A0A0A', hole = '#fff' }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M12 2.4C12.8 6.1 20.2 6.9 20.2 12.7c0 4.1-3.4 6.9-8.2 6.9s-8.2-2.8-8.2-6.9C3.8 6.9 11.2 6.1 12 2.4z" fill={tone} />
      <rect x="13.1" y="13.1" width="9.2" height="9.2" rx="2.5" fill={tone} stroke={hole} strokeWidth="1.7" />
      <circle cx="16.1" cy="16.1" r="1" fill={hole} />
      <circle cx="19.4" cy="19.4" r="1" fill={hole} />
    </svg>
  );
}

// 아이콘은 Lucide 한 벌로 통일한다. 굵기 1.85, 크기는 20/22/24만 쓴다.
function Stroke({ size = 21, width = 1.85, children, fill = 'none' }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill={fill} stroke="currentColor" strokeWidth={width} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{children}</svg>
  );
}

export const BellIcon = ({ size }) => <Stroke size={size}><path d="M10.268 21a2 2 0 0 0 3.464 0" /><path d="M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326" /></Stroke>;
export const ChatIcon = ({ size }) => <Stroke size={size}><path d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z" /></Stroke>;
export const BackIcon = ({ size }) => <Stroke size={size}><path d="m15 18-6-6 6-6" /></Stroke>;
export const CloseIcon = ({ size }) => <Stroke size={size} width={2}><path d="M18 6 6 18M6 6l12 12" /></Stroke>;
export const ArrowIcon = ({ size = 17 }) => <Stroke size={size} width={2}><path d="m9 18 6-6-6-6" /></Stroke>;
export const SearchIcon = ({ size = 18 }) => <Stroke size={size}><circle cx="11" cy="11" r="8" /><path d="m21 21-4.3-4.3" /></Stroke>;
export const PlusIcon = ({ size = 16 }) => <Stroke size={size} width={2.4}><path d="M12 5v14M5 12h14" /></Stroke>;
export const CheckIcon = ({ size = 12, width = 3.4 }) => <Stroke size={size} width={width}><path d="M20 6 9 17l-5-5" /></Stroke>;
export const FilterIcon = ({ size = 14 }) => <Stroke size={size} width={2}><path d="M21 4h-7M10 4H3M21 12h-9M8 12H3M21 20h-5M12 20H3M14 2v4M8 10v4M16 18v4" /></Stroke>;
export const InfoIcon = ({ size = 19 }) => <Stroke size={size}><circle cx="12" cy="12" r="10" /><path d="M12 16v-4M12 8h.01" /></Stroke>;
export const SendIcon = ({ size = 19 }) => <Stroke size={size} width={2}><path d="M14.536 21.686a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11z" /><path d="m21.854 2.147-10.94 10.939" /></Stroke>;
export const CameraIcon = ({ size = 12 }) => <Stroke size={size} width={2}><path d="M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z" /><circle cx="12" cy="13" r="3" /></Stroke>;
export const EyeIcon = ({ size = 18 }) => <Stroke size={size}><path d="M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0" /><circle cx="12" cy="12" r="3" /></Stroke>;
export const MatchIcon = ({ size = 21 }) => <Stroke size={size}><rect x="2" y="10" width="12" height="12" rx="2" /><path d="m17.92 14 3.5-3.5a2.24 2.24 0 0 0 0-3l-5-4.92a2.24 2.24 0 0 0-3 0L10 6M6 18h.01M10 14h.01M15 6h.01M18 9h.01" /></Stroke>;
export const EditIcon = ({ size = 19 }) => <Stroke size={size}><path d="M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z" /><path d="m15 5 4 4" /></Stroke>;
export const EyeOffIcon = ({ size = 18 }) => <Stroke size={size}><path d="M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49" /><path d="M14.084 14.158a3 3 0 0 1-4.242-4.242" /><path d="M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143" /><path d="m2 2 20 20" /></Stroke>;
export const MailIcon = ({ size = 19 }) => <Stroke size={size} width={1.9}><rect x="2" y="4" width="20" height="16" rx="2" /><path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7" /></Stroke>;

/** 뒤로가기 바. 아이콘 옆에 title을 두면 같은 줄에서 제목을 보여준다. 오른쪽 보조 조작은 action으로 넘긴다. */
export function TopBar({ onBack, backLabel = '뒤로 가기', title, action }) {
  return (
    <div className="topbar">
      <button type="button" className="icon-btn" aria-label={backLabel} onClick={onBack}><BackIcon /></button>
      {title && <h1 className="topbar-title"><span>{title}</span></h1>}
      {action}
    </div>
  );
}

export function ScreenTitle({ children, actions }) {
  return <h1 className="screen-title">{children}{actions}</h1>;
}

/**
 * 자리를 미플로 보여 준다. 채워진 자리는 참가 순서 색, 빈 자리는 --off다.
 *
 * 순서 색은 참가자 목록과 같은 규칙이라 카드·상세·만들기 화면에서 같은 자리가 같은 색으로 남는다.
 */
export function Meeples({ filled, total, size = 'sm', animate = false }) {
  const seats = Math.max(0, Number(total) || 0);
  const taken = Math.min(seats, Math.max(0, Number(filled) || 0));
  return (
    <span className={'meeples ' + size} aria-hidden="true">
      {Array.from({ length: seats }, (_, index) => (
        <span
          className={'meeple' + (index < taken ? (animate ? ' pop' : '') : ' empty')}
          key={index}
          style={index < taken ? { color: playerColor(index) } : undefined}
        >
          <i /><b />
        </span>
      ))}
    </span>
  );
}

export function SeatCount({ filled, total, size }) {
  return <span className={'seat-text' + (size === 'lg' ? ' lg' : '')}>{filled} / {total}</span>;
}

export function Cover({ src, className = '', style, fallback = defaultGameCover }) {
  const coverSrc = src || fallback;
  return (
    <span className={'cover ' + className} style={style}>
      <img
        src={coverSrc}
        alt=""
        loading="lazy"
        onError={(e) => {
          if (e.currentTarget.src !== fallback) {
            e.currentTarget.src = fallback;
          }
        }}
      />
    </span>
  );
}

export function Avatar({ name = '', index, imageUrl, color, className = '' }) {
  const resolvedColor = color || playerColor(Number.isInteger(index) ? index : 0);
  if (imageUrl) return <span className={'avatar ' + className}><img src={imageUrl} alt="" /></span>;
  return (
    <span className={'avatar ' + className} style={{ background: resolvedColor, color: playerTextColor(resolvedColor) }} aria-hidden="true">
      {[...name][0] || '?'}
    </span>
  );
}

/** 빈 상태·오류가 함께 쓰는 블록. 제목과 안내 아래에 조작을 둔다. */
export function StateBlock({ title, description, tone, children }) {
  return (
    <section className={'stateblock' + (tone === 'error' ? ' error' : '')} role={tone === 'error' ? 'alert' : undefined}>
      <h2>{title}</h2>
      {description && <p>{description}</p>}
      {children}
    </section>
  );
}

export function ErrorBox({ message, onRetry, title = '불러오지 못했어요' }) {
  return (
    <StateBlock tone="error" title={title} description={message}>
      {onRetry && <button type="button" className="btn" onClick={onRetry}>다시 시도</button>}
    </StateBlock>
  );
}

/** 모임·게임 목록의 불러오는 중 상태. shimmer 3건을 자리로 둔다. */
export function RoomSkeletons({ count = 3, label = '불러오는 중' }) {
  return (
    <div className="skeleton-rooms" role="status" aria-label={label}>
      {Array.from({ length: count }, (_, index) => (
        <div className="skeleton-room" key={index} aria-hidden="true">
          <span className="skeleton" />
          <div>
            <span className="skeleton" style={{ height: 12, width: 88 }} />
            <span className="skeleton" style={{ height: 16, width: '76%' }} />
            <span className="skeleton still" style={{ height: 12, width: '52%' }} />
          </div>
        </div>
      ))}
    </div>
  );
}

export function RankSkeletons({ count = 3 }) {
  return (
    <div className="skeleton-ranks" role="status" aria-label="불러오는 중">
      {Array.from({ length: count }, (_, index) => (
        <div className="skeleton-rank" key={index} aria-hidden="true">
          <span className="skeleton" />
          <div>
            <span className="skeleton" style={{ borderRadius: 6, height: 14, width: '44%' }} />
            <span className="skeleton still" style={{ borderRadius: 6, height: 12, width: '28%' }} />
          </div>
        </div>
      ))}
    </div>
  );
}

export function Pagination({ page, totalPages, loading, onChange, className = '' }) {
  if (!totalPages || totalPages <= 1) return null;
  const windowSize = 3;
  const start = Math.max(0, Math.min(page - Math.floor(windowSize / 2), totalPages - windowSize));
  const end = Math.min(totalPages, start + windowSize);
  const numbers = [];
  for (let index = start; index < end; index += 1) numbers.push(index);
  const go = (next) => { if (next >= 0 && next < totalPages && next !== page) onChange(next); };
  return (
    <nav className={'pagination' + (className ? ' ' + className : '')} aria-label="페이지 이동">
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

/** 게임 정보 출처 표기. 푸터가 없어져 게임 상세와 내정보가 나눠 진다. */
export function BggAttribution({ logoSrc }) {
  return (
    <p className="bgg-note">
      {logoSrc && <a href="https://boardgamegeek.com" target="_blank" rel="noreferrer noopener"><img src={logoSrc} alt="Powered by BGG" /></a>}
      게임 정보는 <a href="https://boardgamegeek.com" target="_blank" rel="noreferrer noopener">BoardGameGeek</a>, 국내 보드게임 자료, 알밤 메이트 팀의 직접 작성·검수와 플레이 경험을 바탕으로 구성했습니다.
    </p>
  );
}
