import { useEffect, useRef, useState } from 'react';
import { ApiError, messageForError } from '../api';

export function isUnauthenticated(error) {
  return error instanceof ApiError && (error.code === 'UNAUTHENTICATED' || error.status === 401);
}

export function useRequest(load, dependencies) {
  const [state, setState] = useState({ data: null, loading: true, error: '' });
  // 오류 블록의 '다시 시도'가 같은 조건으로 다시 부를 수 있게 재조회 신호를 둔다.
  const [refreshVersion, setRefreshVersion] = useState(0);

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    setState((current) => ({ ...current, loading: true, error: '' }));

    load(controller.signal)
      .then((data) => {
        if (active) setState({ data, loading: false, error: '' });
      })
      .catch((error) => {
        if (!active || error?.name === 'AbortError') return;
        setState({ data: null, loading: false, error: messageForError(error) });
      });

    return () => {
      active = false;
      controller.abort();
    };
  }, [refreshVersion, ...dependencies]);

  return { ...state, retry: () => setRefreshVersion((version) => version + 1) };
}

export function usePaginatedRequest(loadPage, dependencies) {
  const [page, setPage] = useState(0);
  const [refreshVersion, setRefreshVersion] = useState(0);
  const [state, setState] = useState({ data: null, loading: true, error: '' });
  const loadPageRef = useRef(loadPage);
  loadPageRef.current = loadPage;

  // 의존성(검색어 등)이 바뀌면 첫 페이지로 되돌린다.
  useEffect(() => { setPage(0); }, dependencies);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    setState((current) => ({ ...current, loading: true, error: '' }));
    loadPageRef.current(page, controller.signal)
      .then((data) => { if (active) setState({ data, loading: false, error: '' }); })
      .catch((error) => {
        if (!active || error?.name === 'AbortError') return;
        // 로그인이 필요한 조건으로 조회했는지 화면이 구분할 수 있게 함께 알린다.
        setState({ data: null, loading: false, error: messageForError(error), unauthenticated: isUnauthenticated(error) });
      });
    return () => { active = false; controller.abort(); };
  }, [page, refreshVersion, ...dependencies]);

  return { ...state, page, setPage, retry: () => setRefreshVersion((version) => version + 1) };
}

const EMPTY_CUMULATIVE = { items: [], page: 0, total: 0, hasNext: false, loading: true, error: '' };

/**
 * 검색 결과를 화면 하단에서 이어 붙인다. 조건이 바뀌면 새 첫 페이지로 교체한다.
 */
export function useCumulativeRequest(loadPage, dependencies) {
  const [state, setState] = useState(EMPTY_CUMULATIVE);
  const [refreshVersion, setRefreshVersion] = useState(0);
  const loadPageRef = useRef(loadPage);
  loadPageRef.current = loadPage;
  const moreControllerRef = useRef(null);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    setState({ ...EMPTY_CUMULATIVE, loading: true });
    loadPageRef.current(0, controller.signal)
      .then((data) => {
        if (!active) return;
        setState({
          items: data.content || [],
          page: data.page ?? 0,
          total: data.totalElements ?? 0,
          hasNext: Boolean(data.hasNext),
          loading: false,
          error: ''
        });
      })
      .catch((error) => {
        if (!active || error?.name === 'AbortError') return;
        setState({ ...EMPTY_CUMULATIVE, loading: false, error: messageForError(error), unauthenticated: isUnauthenticated(error) });
      });
    return () => {
      active = false;
      controller.abort();
      moreControllerRef.current?.abort();
    };
  }, [refreshVersion, ...dependencies]);

  const loadMore = () => {
    if (state.loading || !state.hasNext) return;
    const controller = new AbortController();
    moreControllerRef.current = controller;
    setState((current) => ({ ...current, loading: true }));
    loadPageRef.current(state.page + 1, controller.signal)
      .then((data) => setState((current) => ({
        ...current,
        items: [...current.items, ...(data.content || [])],
        page: data.page ?? current.page + 1,
        total: data.totalElements ?? current.total,
        hasNext: Boolean(data.hasNext),
        loading: false,
        error: ''
      })))
      .catch((error) => {
        if (error?.name === 'AbortError') return;
        setState((current) => ({ ...current, loading: false, error: messageForError(error), unauthenticated: isUnauthenticated(error) }));
      });
  };

  return { ...state, loadMore, retry: () => setRefreshVersion((version) => version + 1) };
}
