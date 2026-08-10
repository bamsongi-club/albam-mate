import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { api } from '../api';
import {
  AGE_BAND_LABEL,
  COMPLEXITY_BANDS,
  EMPTY_GAME_FILTERS,
  EXCLUSIVE_PLAYER_COUNT_OPTIONS,
  PLAYED_FILTER_OPTIONS,
  PLAY_TIME_LABEL,
  PREFERRED_PLAYER_COUNT_OPTIONS,
  THEME_MATCH_OPTIONS
} from './constants';
import { complexityBandOf, exclusivePlayerCountPatch, gameFilterChips } from './filterLogic';
import { useGameMechanisms, useGameOptions } from './hooks';
import {
  CustomPlayerCountInput,
  FilterCheckGroup,
  FilterMultiCheckGroup,
  FilterNumberRangeGroup,
  FilterPanel,
  FilterRadioGroup
} from '../shared/filters';

/**
 * 대표 메커니즘의 설명을 여는 정보 아이콘이다.
 *
 * 데스크톱 hover와 키보드 focus는 상태로 열고, 아이콘을 누르면 여기서 고정한다.
 * 스크롤 목록의 overflow에 잘리지 않도록 설명은 body portal로 렌더링한다.
 * tap은 hover·focus를 함께 일으키므로 상태를 셋으로 나눠 두면 누를 때 도로 닫히는 순서가 생긴다.
 * 화면을 막는 모달을 쓰지 않으므로 다른 조건을 보면서 설명을 확인할 수 있다.
 */
function MechanismHint({ code, name, description }) {
  const [isPinned, setIsPinned] = useState(false);
  const [isHovered, setIsHovered] = useState(false);
  const [isFocused, setIsFocused] = useState(false);
  const [tooltipStyle, setTooltipStyle] = useState({ left: '0px', top: '0px', position: 'fixed', visibility: 'hidden' });
  const [isScrollable, setIsScrollable] = useState(false);
  const buttonRef = useRef(null);
  const tooltipRef = useRef(null);
  const hoverCloseTimerRef = useRef(null);
  const tooltipId = 'mechanism-hint-' + code;
  const isOpen = isPinned || isHovered || isFocused;

  const cancelHoverClose = useCallback(() => {
    window.clearTimeout(hoverCloseTimerRef.current);
    hoverCloseTimerRef.current = null;
  }, []);
  const openHovered = useCallback(() => {
    cancelHoverClose();
    setIsHovered(true);
  }, [cancelHoverClose]);
  const closeHoveredSoon = useCallback(() => {
    cancelHoverClose();
    hoverCloseTimerRef.current = window.setTimeout(() => {
      setIsHovered(false);
      hoverCloseTimerRef.current = null;
    }, 80);
  }, [cancelHoverClose]);

  useEffect(() => () => cancelHoverClose(), [cancelHoverClose]);

  useLayoutEffect(() => {
    if (!isOpen || !buttonRef.current || !tooltipRef.current) {
      setTooltipStyle((current) => current.visibility === 'hidden' ? current : { ...current, visibility: 'hidden' });
      return undefined;
    }

    const updatePosition = () => {
      const buttonRect = buttonRef.current.getBoundingClientRect();
      const tooltipRect = tooltipRef.current.getBoundingClientRect();
      const gap = 7;
      const viewportPadding = 8;
      const maxLeft = Math.max(viewportPadding, window.innerWidth - tooltipRect.width - viewportPadding);
      const centeredLeft = buttonRect.left + (buttonRect.width - tooltipRect.width) / 2;
      const left = Math.min(Math.max(viewportPadding, centeredLeft), maxLeft);
      const canPlaceBelow = buttonRect.bottom + gap + tooltipRect.height <= window.innerHeight - viewportPadding;
      const preferredTop = canPlaceBelow
        ? buttonRect.bottom + gap
        : buttonRect.top - gap - tooltipRect.height;
      const maxTop = Math.max(viewportPadding, window.innerHeight - tooltipRect.height - viewportPadding);
      const top = Math.min(Math.max(viewportPadding, preferredTop), maxTop);
      setTooltipStyle({ left: left + 'px', top: top + 'px', position: 'fixed', visibility: 'visible' });
      setIsScrollable(tooltipRef.current.scrollHeight > tooltipRef.current.clientHeight);
    };

    updatePosition();
    window.addEventListener('resize', updatePosition);
    window.addEventListener('scroll', updatePosition, true);
    return () => {
      window.removeEventListener('resize', updatePosition);
      window.removeEventListener('scroll', updatePosition, true);
    };
  }, [description, isOpen]);

  useEffect(() => {
    if (!isPinned) return undefined;

    // 고정된 툴팁은 fixed portal이라 다른 조건 위에 겹칠 수 있다.
    // 내용이 넘쳐 스크롤이 필요한 경우가 아니면 툴팁 자체를 눌러도 풀어,
    // 겹친 자리를 다시 누르면 그 아래 컨트롤이 클릭을 받을 수 있게 한다.
    const closeIfOutside = (event) => {
      if (buttonRef.current?.contains(event.target)) return;
      if (isScrollable && tooltipRef.current?.contains(event.target)) return;
      setIsPinned(false);
    };
    document.addEventListener('pointerdown', closeIfOutside, true);
    return () => {
      document.removeEventListener('pointerdown', closeIfOutside, true);
    };
  }, [isPinned, isScrollable]);

  useEffect(() => {
    if (!isOpen) return undefined;

    const closeOnEscape = (event) => {
      if (event.key !== 'Escape') return;
      cancelHoverClose();
      setIsPinned(false);
      setIsHovered(false);
      setIsFocused(false);
    };

    document.addEventListener('keydown', closeOnEscape);
    return () => document.removeEventListener('keydown', closeOnEscape);
  }, [cancelHoverClose, isOpen]);

  return (
    <span
      className={'mechanism-hint' + (isPinned ? ' on' : '')}
      onMouseEnter={openHovered}
      onMouseLeave={closeHoveredSoon}
    >
      <button
        type="button"
        className="mechanism-hint-button"
        ref={buttonRef}
        aria-label={name + ' 설명'}
        aria-describedby={tooltipId}
        aria-expanded={isPinned}
        onFocus={() => setIsFocused(true)}
        onBlur={() => setIsFocused(false)}
        onClick={() => {
          cancelHoverClose();
          setIsPinned(!isPinned);
          setIsFocused(false);
          // tap이 앞서 일으킨 mouseEnter는 실제 hover가 아니므로 클릭마다 초기화해
          // 두 번째 tap에서도 aria-expanded와 표시 상태가 함께 닫히게 한다.
          setIsHovered(false);
        }}
      >
        <span aria-hidden="true">i</span>
      </button>
      {createPortal(
        <span
          ref={tooltipRef}
          className="mechanism-hint-text"
          id={tooltipId}
          role="tooltip"
          style={isScrollable ? { ...tooltipStyle, pointerEvents: 'auto' } : tooltipStyle}
          onMouseEnter={openHovered}
          onMouseLeave={closeHoveredSoon}
        >
          {description}
        </span>,
        document.body,
      )}
    </span>
  );
}

// 선택지 API는 대표 8개를 먼저 반환하지만, 계약이 고정한 순서는 화면에서 다시 맞춘다.
function featuredMechanisms(options) {
  return options.filter((option) => option.featuredOrder).sort((left, right) => left.featuredOrder - right.featuredOrder);
}

function advancedMechanisms(options, keyword) {
  const needle = keyword.trim().toLowerCase();
  return options
    .filter((option) => !option.featuredOrder)
    // 검색은 한국어명과 BGG 영문명 모두 맞추고 화면에는 한국어명만 보여 준다.
    .filter((option) => !needle
      || option.nameKo.toLowerCase().includes(needle)
      || option.nameEn.toLowerCase().includes(needle))
    .sort((left, right) => left.nameKo.localeCompare(right.nameKo, 'ko'));
}

function MechanismCheckOption({ option, selected, onToggle }) {
  const description = typeof option.descriptionKo === 'string' ? option.descriptionKo.trim() : '';
  return (
    <div className="mechanism-option">
      <label className="filter-option">
        <input
          type="checkbox"
          checked={selected.includes(option.code)}
          onChange={(event) => onToggle(option.code, event.target.checked)}
        />
        {option.nameKo}
      </label>
      {description && <MechanismHint code={option.code} name={option.nameKo} description={description} />}
    </div>
  );
}

// 대표 8개는 항상 보여 주고 나머지는 접힌 고급 목록에 둔다. 고급 목록은 모바일에서 전체 화면으로 열린다.
function MechanismFilterGroup({ options, selected, onToggle }) {
  const [isAdvancedOpen, setIsAdvancedOpen] = useState(false);
  const [keyword, setKeyword] = useState('');
  return (
    <fieldset className="filter-group mechanism-group">
      <legend>메커니즘</legend>
      <div className="mechanism-featured-list">
        {featuredMechanisms(options).map((option) => (
          <div className="mechanism-featured" key={option.code}>
            <MechanismCheckOption option={option} selected={selected} onToggle={onToggle} />
          </div>
        ))}
      </div>
      <button
        type="button"
        className="mechanism-more"
        aria-expanded={isAdvancedOpen}
        aria-controls="mechanism-advanced"
        onClick={() => setIsAdvancedOpen(!isAdvancedOpen)}
      >
        메커니즘 더 보기
      </button>
      {isAdvancedOpen && (
        <div className="mechanism-advanced" id="mechanism-advanced">
          <input
            type="search"
            className="mechanism-search"
            aria-label="메커니즘 검색"
            placeholder="메커니즘 이름으로 찾기"
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
          />
          <div className="mechanism-advanced-list">
            {advancedMechanisms(options, keyword).map((option) => (
              <MechanismCheckOption key={option.code} option={option} selected={selected} onToggle={onToggle} />
            ))}
          </div>
          <button type="button" className="filter-close" onClick={() => setIsAdvancedOpen(false)}>메커니즘 목록 닫기</button>
        </div>
      )}
    </fieldset>
  );
}

// 테마는 메커니즘과 달리 대표 목록을 서버가 정해 주지 않는다. 가나다순으로 앞쪽 일부를 대표로 보여 주고
// 나머지는 메커니즘과 같은 방식(검색 가능한 접힌 목록)으로 둔다.
const THEME_FEATURED_COUNT = 10;

function sortedThemeOptions(options) {
  return [...options].sort((left, right) => left.label.localeCompare(right.label, 'ko'));
}

function featuredThemeOptions(options) {
  return sortedThemeOptions(options).slice(0, THEME_FEATURED_COUNT);
}

function advancedThemeOptions(options, keyword) {
  const needle = keyword.trim().toLowerCase();
  return sortedThemeOptions(options)
    .slice(THEME_FEATURED_COUNT)
    .filter((option) => !needle || option.label.toLowerCase().includes(needle));
}

// 대표 10개는 항상 보여 주고 나머지는 접힌 목록에 둔다. 모바일에서는 메커니즘처럼 전체 화면으로 열린다.
function ThemeFilterGroup({ options, selected, onToggle, children }) {
  const [isAdvancedOpen, setIsAdvancedOpen] = useState(false);
  const [keyword, setKeyword] = useState('');
  const moreButtonRef = useRef(null);
  // 목록을 닫으면 트리거와 고급 목록이 조건부로 사라진다. 포커스를 되돌려 주지 않으면 키보드
  // 사용자의 포커스가 body로 튀어 다음 조작 위치를 잃는다.
  const closeAdvanced = () => {
    setIsAdvancedOpen(false);
    moreButtonRef.current?.focus();
  };
  return (
    <fieldset className="filter-group filter-group-wide mechanism-group">
      <legend>테마</legend>
      <div className="mechanism-featured-list">
        {featuredThemeOptions(options).map((option) => (
          <label className="filter-option" key={option.value}>
            <input type="checkbox" checked={selected.includes(option.value)} onChange={(event) => onToggle(option.value, event.target.checked)} />
            {option.label}
          </label>
        ))}
      </div>
      <button
        ref={moreButtonRef}
        type="button"
        className="mechanism-more"
        aria-expanded={isAdvancedOpen}
        aria-controls="theme-advanced"
        onClick={() => setIsAdvancedOpen(!isAdvancedOpen)}
      >
        테마 더 보기
      </button>
      {isAdvancedOpen && (
        <div className="mechanism-advanced" id="theme-advanced">
          <input
            type="search"
            className="mechanism-search"
            aria-label="테마 검색"
            placeholder="테마 이름으로 찾기"
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
          />
          <div className="mechanism-advanced-list">
            {advancedThemeOptions(options, keyword).map((option) => (
              <label className="filter-option" key={option.value}>
                <input type="checkbox" checked={selected.includes(option.value)} onChange={(event) => onToggle(option.value, event.target.checked)} />
                {option.label}
              </label>
            ))}
          </div>
          <button type="button" className="filter-close" onClick={closeAdvanced}>테마 목록 닫기</button>
        </div>
      )}
      {children}
    </fieldset>
  );
}

export function GameFilters({ filters, onChange, searchSlot }) {
  const mechanismOptions = useGameMechanisms();
  const categoryOptions = useGameOptions(api.getGameCategories);
  const themeOptions = useGameOptions(api.getGameThemes);
  const update = (patch) => onChange({ ...filters, ...patch });
  const toggleIn = (key) => (value, checked) => update({
    [key]: checked ? [...filters[key], value] : filters[key].filter((selected) => selected !== value)
  });
  const selectBand = (value) => {
    const band = COMPLEXITY_BANDS.find((option) => option.value === value);
    update({ complexityMin: band ? band.min : '', complexityMax: band ? band.max : '' });
  };
  // 범위를 직접 입력하면 전용 인원 선택을 되비추던 상태가 아니게 되므로 선택을 해제한다.
  // 되비추던 값도 함께 비운다. 남겨 두면 최소만 바꿔도 전용 인원이 넣어 둔 최대·정확히 일치가
  // 그대로 따라가 `playerCountMin=3&playerCountMax=2&playerCountExact=true` 같은 검증 오류가 된다.
  const updateRange = (patch) => update({
    ...(filters.exclusivePlayerCount.length ? { playerCountMin: '', playerCountMax: '', playerCountExact: false } : null),
    ...patch,
    exclusivePlayerCount: []
  });
  const toggleExclusive = (value, checked) => update(exclusivePlayerCountPatch(
    checked
      ? [...filters.exclusivePlayerCount, value]
      : filters.exclusivePlayerCount.filter((selected) => selected !== value)
  ));
  const togglePlayTime = (value, checked) => update({
    playTime: checked ? [...filters.playTime, value] : filters.playTime.filter((selected) => selected !== value)
  });
  const toggleAgeBand = (value, checked) => update({
    ageBand: checked ? [...filters.ageBand, value] : filters.ageBand.filter((selected) => selected !== value)
  });
  const toggleMechanism = (code, checked) => update({
    mechanism: checked ? [...filters.mechanism, code] : filters.mechanism.filter((selected) => selected !== code)
  });
  return (
    <FilterPanel chips={gameFilterChips(filters, onChange, mechanismOptions, categoryOptions, themeOptions)} onReset={() => onChange(EMPTY_GAME_FILTERS)} searchSlot={searchSlot}>
      <FilterRadioGroup name="game-filter-played" label="해 본 게임" value={filters.playedFilter}
        onChange={(playedFilter) => update({ playedFilter })} options={PLAYED_FILTER_OPTIONS} />
      <FilterCheckGroup label="모임" checked={filters.upcomingOnly} onChange={(upcomingOnly) => update({ upcomingOnly })} text="예정 모임 있는 게임만" />
      <FilterMultiCheckGroup label="카테고리" values={filters.category} onToggle={toggleIn('category')}
        options={categoryOptions.map((option) => ({ value: option.code, label: option.nameKo }))} />
      <FilterMultiCheckGroup label="연령대" values={filters.ageBand} onToggle={toggleAgeBand}
        options={Object.entries(AGE_BAND_LABEL).map(([code, label]) => ({ value: code, label }))} />
      <FilterRadioGroup name="game-filter-complexity" label="게임 난이도" value={complexityBandOf(filters)?.value || ''} onChange={selectBand}
        options={[{ value: '', label: '전체' }, ...COMPLEXITY_BANDS.map((band) => ({ value: band.value, label: band.label }))]} />
      <FilterMultiCheckGroup label="플레이 시간" values={filters.playTime} onToggle={togglePlayTime}
        options={Object.entries(PLAY_TIME_LABEL).map(([code, label]) => ({ value: code, label }))} />
      <FilterNumberRangeGroup rowStart label="게임 인원" unit="명" min={filters.playerCountMin} max={filters.playerCountMax}
        onMinChange={(playerCountMin) => updateRange({ playerCountMin })} onMaxChange={(playerCountMax) => updateRange({ playerCountMax })}>
        <label className="filter-option filter-option-picker">
          <input type="checkbox" checked={filters.playerCountExact} onChange={(event) => updateRange({ playerCountExact: event.target.checked })} />
          인원 정확히 일치
        </label>
        {/* 범위 조건과 전용 인원은 서로 전환하는 조건이라 같은 칼럼에서 구분선으로 나눈다. */}
        <hr className="filter-group-divider" />
        {EXCLUSIVE_PLAYER_COUNT_OPTIONS.map((option) => (
          <label className="filter-option" key={option.value}>
            <input
              type="checkbox"
              checked={filters.exclusivePlayerCount.includes(option.value)}
              onChange={(event) => toggleExclusive(option.value, event.target.checked)}
            />
            {option.label}
          </label>
        ))}
      </FilterNumberRangeGroup>
      <FilterMultiCheckGroup label="추천 인원" values={filters.recommendedPlayerCount} onToggle={toggleIn('recommendedPlayerCount')}
        options={PREFERRED_PLAYER_COUNT_OPTIONS}>
        <CustomPlayerCountInput label="추천 인원" values={filters.recommendedPlayerCount}
          onAdd={(value) => update({ recommendedPlayerCount: [...filters.recommendedPlayerCount, value] })} />
      </FilterMultiCheckGroup>
      <FilterMultiCheckGroup label="베스트 인원" values={filters.bestPlayerCount} onToggle={toggleIn('bestPlayerCount')}
        options={PREFERRED_PLAYER_COUNT_OPTIONS}>
        <CustomPlayerCountInput label="베스트 인원" values={filters.bestPlayerCount}
          onAdd={(value) => update({ bestPlayerCount: [...filters.bestPlayerCount, value] })} />
      </FilterMultiCheckGroup>
      {/* 테마를 하나만 고르면 포함 방식이 결과를 바꾸지 않으므로 둘 이상일 때만 보여 준다. */}
      <ThemeFilterGroup options={themeOptions.map((option) => ({ value: option.code, label: option.nameKo }))}
        selected={filters.theme} onToggle={toggleIn('theme')}>
        {filters.theme.length > 1 && (
          <>
            <hr className="filter-group-divider" />
            {THEME_MATCH_OPTIONS.map((option) => (
              <label className="filter-option" key={option.value || 'any'}>
                <input
                  type="radio"
                  name="game-filter-theme-match"
                  checked={(filters.themeMatch || '') === option.value}
                  onChange={() => update({ themeMatch: option.value })}
                />
                {option.label}
              </label>
            ))}
          </>
        )}
      </ThemeFilterGroup>
      <MechanismFilterGroup options={mechanismOptions} selected={filters.mechanism} onToggle={toggleMechanism} />
    </FilterPanel>
  );
}
