import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { api, messageForError } from '../api';
import { GAME_SEARCH_DEBOUNCE_MS, GAME_SEARCH_PAGE_SIZE } from './constants';
import { normalizeGameSummary } from './data';
import { CheckIcon, Cover, SearchIcon } from '../shared/ui';
import { useSheetDragClose } from '../shared/sheetDrag';

export function GamePickerDialog({ isOpen, selectedGameId, allowClear, onSelect, onClear, onClose }) {
  const [query, setQuery] = useState('');
  const [pageData, setPageData] = useState({ content: [], page: 0, size: GAME_SEARCH_PAGE_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const searchInputRef = useRef(null);
  const searchGenerationRef = useRef(0);
  const isOpenRef = useRef(isOpen);
  const searchControllerRef = useRef(null);
  const loadMoreControllerRef = useRef(null);

  const invalidateSearch = useCallback(() => {
    searchGenerationRef.current += 1;
    searchControllerRef.current?.abort();
    searchControllerRef.current = null;
    loadMoreControllerRef.current?.abort();
    loadMoreControllerRef.current = null;
    return searchGenerationRef.current;
  }, []);

  const handleClose = useCallback(() => {
    isOpenRef.current = false;
    invalidateSearch();
    setLoading(false);
    onClose();
  }, [invalidateSearch, onClose]);
  const { sheetStyle, gripProps } = useSheetDragClose(handleClose);

  const handleQueryChange = (event) => {
    invalidateSearch();
    setPageData({ content: [], page: 0, size: GAME_SEARCH_PAGE_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
    setError('');
    setLoading(false);
    setQuery(event.target.value);
  };

  useLayoutEffect(() => {
    const wasOpen = isOpenRef.current;
    isOpenRef.current = isOpen;
    if (wasOpen && !isOpen) {
      invalidateSearch();
      setLoading(false);
    }
  }, [isOpen, invalidateSearch]);

  useEffect(() => {
    if (!isOpen) return;
    setQuery('');
    setPageData({ content: [], page: 0, size: GAME_SEARCH_PAGE_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
    setError('');
    setLoading(false);
  }, [isOpen]);

  useEffect(() => {
    const generation = invalidateSearch();
    if (!isOpen) return undefined;
    const keyword = query.trim();
    if (!keyword) {
      setPageData({ content: [], page: 0, size: GAME_SEARCH_PAGE_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
      setError('');
      setLoading(false);
      return undefined;
    }

    let canceled = false;
    const controller = new AbortController();
    searchControllerRef.current = controller;
    const timer = window.setTimeout(async () => {
      setLoading(true);
      setError('');
      try {
        const result = await api.getGames({ keyword, page: 0, size: GAME_SEARCH_PAGE_SIZE }, controller.signal);
        if (!canceled && isOpenRef.current && generation === searchGenerationRef.current) setPageData({ ...result, content: (result.content || []).map(normalizeGameSummary) });
      } catch (requestError) {
        if (!canceled && isOpenRef.current && generation === searchGenerationRef.current && requestError?.name !== 'AbortError') setError(messageForError(requestError, '게임 목록을 불러오지 못했어요.'));
      } finally {
        if (!canceled && isOpenRef.current && generation === searchGenerationRef.current) setLoading(false);
        if (searchControllerRef.current === controller) searchControllerRef.current = null;
      }
    }, GAME_SEARCH_DEBOUNCE_MS);

    return () => {
      canceled = true;
      window.clearTimeout(timer);
      if (searchControllerRef.current === controller) searchControllerRef.current = null;
      controller.abort();
      loadMoreControllerRef.current?.abort();
      loadMoreControllerRef.current = null;
    };
  }, [isOpen, query]);

  useEffect(() => {
    if (!isOpen) return undefined;
    const focusTimer = window.setTimeout(() => searchInputRef.current?.focus(), 0);
    const handleKeyDown = (event) => {
      if (event.key === 'Escape') handleClose();
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => {
      window.clearTimeout(focusTimer);
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [isOpen, handleClose]);

  if (!isOpen) return null;

  const hasQuery = Boolean(query.trim());
  const loadMore = async () => {
    if (loading || !pageData.hasNext) return;
    const keyword = query.trim();
    const generation = searchGenerationRef.current;
    const controller = new AbortController();
    loadMoreControllerRef.current = controller;
    setLoading(true);
    setError('');
    try {
      const nextPage = await api.getGames({ keyword, page: pageData.page + 1, size: GAME_SEARCH_PAGE_SIZE }, controller.signal);
      if (isOpenRef.current && generation === searchGenerationRef.current && query.trim() === keyword) {
        setPageData((current) => ({ ...nextPage, content: [...current.content, ...(nextPage.content || []).map(normalizeGameSummary)] }));
      }
    } catch (requestError) {
      if (isOpenRef.current && generation === searchGenerationRef.current && requestError?.name !== 'AbortError') {
        setError(messageForError(requestError, '게임 목록을 불러오지 못했어요.'));
      }
    } finally {
      if (isOpenRef.current && generation === searchGenerationRef.current) {
        loadMoreControllerRef.current = null;
        setLoading(false);
      }
    }
  };

  return (
    <div className="sheet-backdrop" role="presentation" onMouseDown={handleClose}>
      <section className="sheet tall" role="dialog" aria-modal="true" aria-labelledby="game-picker-title" style={sheetStyle} onMouseDown={(event) => event.stopPropagation()}>
        <span className="sheet-grip" aria-hidden="true" {...gripProps}><span className="sheet-handle" /></span>
        <div className="sheet-head">
          <h2 id="game-picker-title">게임 선택</h2>
          <button type="button" className="sheet-reset" aria-label="게임 검색 닫기" onClick={handleClose}>닫기</button>
        </div>
        <div className="searchbox" style={{ flex: 'none', marginTop: 14 }}>
          <SearchIcon />
          <input ref={searchInputRef} value={query} onChange={handleQueryChange} placeholder="게임 이름으로 검색" aria-label="게임 이름 검색" />
        </div>
        {allowClear && (
          <button type="button" className="btn fill sm" style={{ flex: 'none', marginTop: 10 }} onClick={() => { onClear(); handleClose(); }}>게임 선택 안 함</button>
        )}
        <div className="picker-list nos">
          {!hasQuery && <p className="picker-state">게임 이름을 입력하면 검색 결과를 불러와요.</p>}
          {hasQuery && !error && <p className="section-label">{loading && !pageData.content.length ? '검색 중…' : '검색 결과 ' + pageData.totalElements + '개'}</p>}
          {error && <p className="picker-state error" role="alert">{error}</p>}
          {!loading && hasQuery && !error && !pageData.content.length && <p className="picker-state">일치하는 게임이 없어요. 다른 이름으로 검색해보세요.</p>}
          {pageData.content.map((game) => {
            const selected = String(game.id) === String(selectedGameId);
            return (
              <button type="button" className="picker-row" key={game.id} aria-pressed={selected} onClick={() => { onSelect(game); handleClose(); }}>
                <Cover src={game.imageUrl} />
                <span className="picker-row-copy">
                  <strong>{game.title}</strong>
                  <span>{[game.englishName, game.players, game.time].filter(Boolean).join(' · ')}</span>
                </span>
                {selected && <span className="picker-check"><CheckIcon /></span>}
              </button>
            );
          })}
          {pageData.hasNext && <button type="button" className="more-btn" disabled={loading} onClick={loadMore}>{loading ? '불러오는 중…' : '검색 결과 더 보기'}</button>}
        </div>
      </section>
    </div>
  );
}
