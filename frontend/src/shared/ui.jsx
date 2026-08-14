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

function Stroke({ size = 21, width = 2, children, fill = 'none' }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill={fill} stroke="currentColor" strokeWidth={width} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{children}</svg>
  );
}

export const BellIcon = ({ size }) => <Stroke size={size} width={1.8}><path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0" /></Stroke>;
export const ChatIcon = ({ size }) => <Stroke size={size} width={1.8}><path d="M21 12a8 8 0 0 1-11.5 7.2L4 21l1.8-5A8 8 0 1 1 21 12z" /></Stroke>;
export const BackIcon = ({ size }) => <Stroke size={size}><path d="m14 6-6 6 6 6" /></Stroke>;
export const CloseIcon = ({ size }) => <Stroke size={size}><path d="M18 6 6 18M6 6l12 12" /></Stroke>;
export const ArrowIcon = ({ size = 17 }) => <Stroke size={size} width={2.2}><path d="m10 6 6 6-6 6" /></Stroke>;
export const SearchIcon = ({ size = 18 }) => <Stroke size={size}><circle cx="11" cy="11" r="7" /><path d="m20 20-3.6-3.6" /></Stroke>;
export const PlusIcon = ({ size = 16 }) => <Stroke size={size} width={2.6}><path d="M12 5v14M5 12h14" /></Stroke>;
export const CheckIcon = ({ size = 12, width = 3.4 }) => <Stroke size={size} width={width}><path d="M20 6 9 17l-5-5" /></Stroke>;
export const FilterIcon = ({ size = 14 }) => <Stroke size={size} width={2.2}><path d="M3 6h18M7 12h10M11 18h2" /></Stroke>;
export const InfoIcon = ({ size = 19 }) => <Stroke size={size}><circle cx="12" cy="12" r="9" /><path d="M12 16v-4M12 8h.01" /></Stroke>;
export const SendIcon = ({ size = 19 }) => <Stroke size={size} width={2.2}><path d="M4 12 20 4l-7 16-2-7z" /></Stroke>;
export const CameraIcon = ({ size = 12 }) => <Stroke size={size} width={2.2}><path d="M4 8.5h3l1.4-2h7.2L17 8.5h3v10H4zM12 16a3.2 3.2 0 1 1 0-6.4 3.2 3.2 0 0 1 0 6.4" /></Stroke>;
export const EyeIcon = ({ size = 18 }) => <Stroke size={size}><path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7z" /><circle cx="12" cy="12" r="3" /></Stroke>;
export const MatchIcon = ({ size = 21 }) => <Stroke size={size} width={1.9}><path d="M4 6h16v10H4zM9 20h6M12 16v4" /></Stroke>;
export const EditIcon = ({ size = 19 }) => <Stroke size={size} width={1.9}><path d="M17 3a2.1 2.1 0 0 1 3 3L8 18l-4 1 1-4Z" /></Stroke>;
export const EyeOffIcon = ({ size = 18 }) => <Stroke size={size}><path d="M17.94 17.94A10.94 10.94 0 0 1 12 19c-7 0-11-7-11-7a18.6 18.6 0 0 1 4.22-5.06M9.9 4.24A10.94 10.94 0 0 1 12 4c7 0 11 7 11 7a18.6 18.6 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" /><path d="M1 1l22 22" /></Stroke>;

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

export function Avatar({ name = '', index, imageUrl, className = '' }) {
  const color = playerColor(Number.isInteger(index) ? index : 0);
  if (imageUrl) return <span className={'avatar ' + className}><img src={imageUrl} alt="" /></span>;
  return (
    <span className={'avatar ' + className} style={{ background: color, color: playerTextColor(color) }} aria-hidden="true">
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

/** 게임 정보 출처 표기. 푸터가 없어져 게임 상세와 내정보가 나눠 진다. */
export function BggAttribution({ logoSrc }) {
  return (
    <p className="bgg-note">
      {logoSrc && <a href="https://boardgamegeek.com" target="_blank" rel="noreferrer noopener"><img src={logoSrc} alt="Powered by BGG" /></a>}
      게임 정보는 <a href="https://boardgamegeek.com" target="_blank" rel="noreferrer noopener">BoardGameGeek</a>, 국내 보드게임 자료, 알밤 메이트 팀의 직접 작성·검수와 플레이 경험을 바탕으로 구성했습니다.
    </p>
  );
}
