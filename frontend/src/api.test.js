import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, clearCsrfToken, setUnauthenticatedHandler } from './api';

function successfulResponse(data) {
  return new Response(JSON.stringify({ status: 200, data }), {
    status: 200,
    headers: { 'content-type': 'application/json' }
  });
}

afterEach(() => {
  clearCsrfToken();
  setUnauthenticatedHandler(undefined);
  vi.unstubAllGlobals();
});

describe('알림 조회 API', () => {
  it('목록 첫 페이지를 계약된 query parameter로 조회한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(successfulResponse({ content: [] }));
    vi.stubGlobal('fetch', fetchMock);
    const controller = new AbortController();

    await api.getNotifications({ page: 0, size: 10 }, controller.signal);

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/users/me/notifications?page=0&size=10',
      expect.objectContaining({ method: 'GET', credentials: 'include', signal: controller.signal })
    );
  });

  it('미확인 개수를 별도 GET 경로로 조회한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(successfulResponse({ unreadCount: 3 }));
    vi.stubGlobal('fetch', fetchMock);

    await api.getUnreadNotificationCount();

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/users/me/notifications/unread-count',
      expect.objectContaining({ method: 'GET', credentials: 'include' })
    );
  });

  it('현재 인증 세대의 모든 401을 공통 세션 만료 흐름으로 보낸다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      status: 401,
      code: 'REQUEST_FAILED',
      message: '로그인이 필요합니다.'
    }), {
      status: 401,
      headers: { 'content-type': 'application/json' }
    }));
    const unauthenticatedHandler = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    setUnauthenticatedHandler(unauthenticatedHandler);

    await expect(api.getUnreadNotificationCount()).rejects.toMatchObject({ status: 401 });

    expect(unauthenticatedHandler).toHaveBeenCalledOnce();
  });

  it('로그인으로 인증 세대가 바뀌면 이전 사용자의 늦은 성공 응답을 폐기한다', async () => {
    let resolvePreviousProfile;
    const previousProfileResponse = new Promise((resolve) => {
      resolvePreviousProfile = resolve;
    });
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => previousProfileResponse)
      .mockResolvedValueOnce(successfulResponse({ headerName: 'X-CSRF-TOKEN', token: 'csrf-token' }))
      .mockResolvedValueOnce(successfulResponse({ id: 2, nickname: '새 사용자' }));
    vi.stubGlobal('fetch', fetchMock);

    const previousProfileRequest = api.getMyProfile();
    await api.login({ email: 'new@example.com', password: 'password' });
    resolvePreviousProfile(successfulResponse({ id: 1, nickname: '이전 사용자' }));

    await expect(previousProfileRequest).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('인증 세대가 바뀐 뒤 이전 요청의 네트워크 실패를 AbortError로 정규화한다', async () => {
    let rejectPreviousProfile;
    const previousProfileResponse = new Promise((resolve, reject) => {
      rejectPreviousProfile = reject;
    });
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => previousProfileResponse)
      .mockResolvedValueOnce(successfulResponse({ headerName: 'X-CSRF-TOKEN', token: 'csrf-token' }))
      .mockResolvedValueOnce(successfulResponse({ id: 2, nickname: '새 사용자' }));
    vi.stubGlobal('fetch', fetchMock);

    const previousProfileRequest = api.getMyProfile();
    await api.login({ email: 'new@example.com', password: 'password' });
    rejectPreviousProfile(new TypeError('network failure'));

    await expect(previousProfileRequest).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('인증 세대가 바뀐 뒤 이전 요청의 JSON 파싱 실패를 AbortError로 정규화한다', async () => {
    let resolvePreviousProfile;
    const previousProfileResponse = new Promise((resolve) => {
      resolvePreviousProfile = resolve;
    });
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => previousProfileResponse)
      .mockResolvedValueOnce(successfulResponse({ headerName: 'X-CSRF-TOKEN', token: 'csrf-token' }))
      .mockResolvedValueOnce(successfulResponse({ id: 2, nickname: '새 사용자' }));
    vi.stubGlobal('fetch', fetchMock);

    const previousProfileRequest = api.getMyProfile();
    await api.login({ email: 'new@example.com', password: 'password' });
    resolvePreviousProfile(new Response('{invalid-json', {
      status: 200,
      headers: { 'content-type': 'application/json' }
    }));

    await expect(previousProfileRequest).rejects.toMatchObject({ name: 'AbortError' });
  });
});

function stubFetch() {
  const fetchMock = vi.fn().mockResolvedValue(successfulResponse({ content: [] }));
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function requestedUrl(fetchMock) {
  return fetchMock.mock.calls[0][0];
}

describe('게임 목록 검색 API', () => {
  it('인원·시간·복잡도 필터를 계약된 이름과 값으로 전달한다', async () => {
    const fetchMock = stubFetch();

    await api.getGames({
      keyword: '루미',
      upcomingOnly: true,
      playerCount: '10',
      playTime: 'MEDIUM',
      complexityMin: '2',
      complexityMax: '4',
      page: 0,
      size: 24
    });

    expect(requestedUrl(fetchMock)).toBe(
      '/api/games?keyword=%EB%A3%A8%EB%AF%B8&upcomingOnly=true&playerCount=10&playTime=MEDIUM&complexityMin=2&complexityMax=4&page=0&size=24'
    );
  });

  it('선택하지 않은 필터를 생략해 필터 없는 목록 요청과 같은 요청을 보낸다', async () => {
    const fetchMock = stubFetch();

    await api.getGames({
      keyword: '',
      upcomingOnly: false,
      playerCount: '',
      playTime: '',
      complexityMin: '',
      complexityMax: '',
      page: 0,
      size: 24
    });

    expect(requestedUrl(fetchMock)).toBe('/api/games?upcomingOnly=false&page=0&size=24');
  });
});

describe('방 목록 검색 API', () => {
  it('날짜 범위·남은 자리·룰마스터 필터를 계약된 이름과 값으로 전달한다', async () => {
    const fetchMock = stubFetch();

    await api.getRooms({
      startsAtFrom: '2026-08-10T00:00:00+09:00',
      startsAtTo: '2026-08-12T00:00:00+09:00',
      minRemainingSeats: '3',
      rulemasterOnly: true,
      page: 0,
      size: 12
    });

    expect(requestedUrl(fetchMock)).toBe(
      '/api/rooms?startsAtFrom=2026-08-10T00%3A00%3A00%2B09%3A00&startsAtTo=2026-08-12T00%3A00%3A00%2B09%3A00'
        + '&minRemainingSeats=3&rulemasterOnly=true&page=0&size=12'
    );
  });

  it('경험 수준 다중 선택을 같은 이름의 반복 parameter로 전달한다', async () => {
    const fetchMock = stubFetch();

    await api.getRooms({ experienceLevels: ['BEGINNER_WELCOME', 'ALL_LEVELS'], page: 0, size: 12 });

    expect(requestedUrl(fetchMock)).toBe(
      '/api/rooms?experienceLevels=BEGINNER_WELCOME&experienceLevels=ALL_LEVELS&page=0&size=12'
    );
  });

  it('룰마스터 진행 조건을 선택하지 않으면 rulemasterOnly를 보내지 않는다', async () => {
    const fetchMock = stubFetch();

    await api.getRooms({ rulemasterOnly: false, page: 0, size: 12 });

    expect(requestedUrl(fetchMock)).toBe('/api/rooms?page=0&size=12');
  });

  it('선택하지 않은 필터를 생략해 필터 없는 목록 요청과 같은 요청을 보낸다', async () => {
    const fetchMock = stubFetch();

    await api.getRooms({
      type: '',
      keyword: '',
      startsAtFrom: undefined,
      startsAtTo: undefined,
      minRemainingSeats: '',
      experienceLevels: [],
      rulemasterOnly: false,
      page: 0,
      size: 12
    });

    expect(requestedUrl(fetchMock)).toBe('/api/rooms?page=0&size=12');
  });
});
