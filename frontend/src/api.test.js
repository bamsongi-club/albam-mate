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

  it('#272 T9 단건·일괄 읽음은 mutate 경계에서 현재 CSRF 토큰을 전송한다', async () => {
    const updatedNotification = { id: 7, readAt: '2026-08-04T09:00:00+09:00' };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(successfulResponse({
        headerName: 'X-CSRF-TOKEN',
        token: 'current-csrf-token'
      }))
      .mockResolvedValueOnce(successfulResponse(updatedNotification))
      .mockResolvedValueOnce(successfulResponse({
        updatedCount: 2,
        boundaryNotificationId: 9
      }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(api.markNotificationRead(7)).resolves.toEqual(updatedNotification);
    await api.markAllNotificationsRead();

    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/users/me/notifications/7',
      expect.objectContaining({
        method: 'PATCH',
        credentials: 'include',
        headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'current-csrf-token' }),
        body: JSON.stringify({ read: true })
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      '/api/users/me/notifications',
      expect.objectContaining({
        method: 'PATCH',
        credentials: 'include',
        headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'current-csrf-token' }),
        body: JSON.stringify({ read: true })
      })
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
  it('인원 범위·시간·복잡도 필터를 계약된 이름과 값으로 전달한다', async () => {
    const fetchMock = stubFetch();

    await api.getGames({
      keyword: '루미',
      upcomingOnly: true,
      playerCountMin: '2',
      playerCountMax: '4',
      playerCountExact: true,
      exclusivePlayerCount: [],
      playTime: ['UP_TO_10', 'AT_LEAST_90'],
      complexityMin: '2',
      complexityMax: '4',
      page: 0,
      size: 24
    });

    expect(requestedUrl(fetchMock)).toBe(
      '/api/games?keyword=%EB%A3%A8%EB%AF%B8&upcomingOnly=true&playerCountMin=2&playerCountMax=4'
        + '&playerCountExact=true&playTime=UP_TO_10&playTime=AT_LEAST_90'
        + '&complexityMin=2&complexityMax=4&page=0&size=24'
    );
  });

  it('전용 인원은 같은 이름을 반복해 전달한다', async () => {
    const fetchMock = stubFetch();

    await api.getGames({ exclusivePlayerCount: ['1', '2'], playTime: [], page: 0, size: 24 });

    expect(requestedUrl(fetchMock)).toBe(
      '/api/games?exclusivePlayerCount=1&exclusivePlayerCount=2&page=0&size=24'
    );
  });

  it('선택하지 않은 필터를 생략해 필터 없는 목록 요청과 같은 요청을 보낸다', async () => {
    const fetchMock = stubFetch();

    await api.getGames({
      keyword: '',
      upcomingOnly: false,
      playerCountMin: '',
      playerCountMax: '',
      playerCountExact: false,
      exclusivePlayerCount: [],
      playTime: [],
      complexityMin: '',
      complexityMax: '',
      page: 0,
      size: 24
    });

    expect(requestedUrl(fetchMock)).toBe('/api/games?upcomingOnly=false&playerCountExact=false&page=0&size=24');
  });

  it('메커니즘은 같은 이름을 반복해 전달한다', async () => {
    const fetchMock = stubFetch();

    await api.getGames({ exclusivePlayerCount: [], playTime: [], mechanism: ['HAND_MANAGEMENT', 'DICE_ROLLING'], page: 0, size: 24 });

    expect(requestedUrl(fetchMock)).toBe(
      '/api/games?mechanism=HAND_MANAGEMENT&mechanism=DICE_ROLLING&page=0&size=24'
    );
  });
});

describe('해 본 게임 API', () => {
  it('관계 필터는 단일 값으로 전달하고 선택하지 않으면 생략한다', async () => {
    const fetchMock = stubFetch();

    await api.getGames({ exclusivePlayerCount: [], playTime: [], mechanism: [], playedFilter: 'PLAYED_ONLY', page: 0, size: 24 });

    expect(requestedUrl(fetchMock)).toBe('/api/games?playedFilter=PLAYED_ONLY&page=0&size=24');
  });

  it('표시·취소는 mutate 경계에서 현재 CSRF 토큰을 전송한다', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(successfulResponse({ headerName: 'X-CSRF-TOKEN', token: 'current-csrf-token' }))
      .mockResolvedValueOnce(successfulResponse({ gameId: 7, playedByMe: true }))
      .mockResolvedValueOnce(successfulResponse({ gameId: 7, playedByMe: false }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(api.markGamePlayed(7)).resolves.toEqual({ gameId: 7, playedByMe: true });
    await expect(api.unmarkGamePlayed(7)).resolves.toEqual({ gameId: 7, playedByMe: false });

    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/users/me/played-games/7',
      expect.objectContaining({ method: 'PUT', credentials: 'include', headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'current-csrf-token' }) })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      '/api/users/me/played-games/7',
      expect.objectContaining({ method: 'DELETE', credentials: 'include', headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'current-csrf-token' }) })
    );
  });
});

describe('게임 메커니즘 선택지 API', () => {
  it('공개 선택지를 조건 없는 GET 경로로 조회한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(successfulResponse([]));
    vi.stubGlobal('fetch', fetchMock);
    const controller = new AbortController();

    await api.getGameMechanisms(controller.signal);

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/game-mechanisms',
      expect.objectContaining({ method: 'GET', credentials: 'include', signal: controller.signal })
    );
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

describe('채팅 API', () => {
  it('메시지 이력 커서와 크기를 query parameter로 전달한다', async () => {
    const fetchMock = stubFetch();

    await api.getChatMessages('7', { beforeMessageId: 42, size: 20 });

    expect(requestedUrl(fetchMock)).toBe('/api/rooms/7/chat/messages?beforeMessageId=42&size=20');
  });

  it('메시지 전송은 CSRF 토큰과 clientMessageId를 함께 보낸다', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(successfulResponse({ headerName: 'X-CSRF-TOKEN', token: 'csrf-token' }))
      .mockResolvedValueOnce(successfulResponse({ messageId: 8 }));
    vi.stubGlobal('fetch', fetchMock);
    const controller = new AbortController();

    await api.sendChatMessage('7', { clientMessageId: 'retry-key', content: '안녕하세요' }, controller.signal);

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/auth/csrf', expect.objectContaining({ signal: undefined }));
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/rooms/7/chat/messages', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'csrf-token' }),
      body: JSON.stringify({ clientMessageId: 'retry-key', content: '안녕하세요' }),
      signal: controller.signal
    }));
  });

  it('공유 CSRF 대기 중 채팅 취소가 다른 mutation을 취소하지 않는다', async () => {
    let resolveCsrf;
    const pendingCsrf = new Promise((resolve) => {
      resolveCsrf = resolve;
    });
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => pendingCsrf)
      .mockImplementation((_path, options) => {
        if (options.signal?.aborted) return Promise.reject(options.signal.reason);
        return Promise.resolve(successfulResponse({}));
      });
    vi.stubGlobal('fetch', fetchMock);
    const chatController = new AbortController();

    const chatSend = api.sendChatMessage('7', { clientMessageId: 'chat-key', content: '안녕하세요' }, chatController.signal);
    const otherMutation = api.markGamePlayed(42);
    const settled = Promise.allSettled([chatSend, otherMutation]);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    chatController.abort();
    resolveCsrf(successfulResponse({ headerName: 'X-CSRF-TOKEN', token: 'shared-csrf-token' }));

    const [chatResult, otherMutationResult] = await settled;
    expect(chatResult).toMatchObject({ status: 'rejected', reason: { name: 'AbortError' } });
    expect(otherMutationResult).toMatchObject({ status: 'fulfilled', value: {} });
    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/auth/csrf',
      expect.objectContaining({ signal: undefined })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/users/me/played-games/42',
      expect.objectContaining({ method: 'PUT', signal: undefined })
    );
  });

  it.each([503, 429])('HTTP %i 오류 응답의 본문 파싱이 deadline으로 중단돼도 ApiError를 유지한다', async (status) => {
    let rejectBodyParsing;
    let notifyBodyParsingStarted;
    const bodyParsingStarted = new Promise((resolve) => {
      notifyBodyParsingStarted = resolve;
    });
    const errorResponse = {
      ok: false,
      status,
      headers: new Headers({ 'content-type': 'application/json', 'retry-after': '2' }),
      json: vi.fn(() => {
        notifyBodyParsingStarted();
        return new Promise((_resolve, reject) => {
          rejectBodyParsing = reject;
        });
      })
    };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(successfulResponse({ headerName: 'X-CSRF-TOKEN', token: 'csrf-token' }))
      .mockResolvedValueOnce(errorResponse);
    vi.stubGlobal('fetch', fetchMock);
    const controller = new AbortController();
    const send = api.sendChatMessage('7', { clientMessageId: 'retry-key', content: '안녕하세요' }, controller.signal);

    await bodyParsingStarted;
    controller.abort();
    const aborted = new Error('response body aborted');
    aborted.name = 'AbortError';
    rejectBodyParsing(aborted);

    await expect(send).rejects.toMatchObject({
      name: 'ApiError',
      status,
      code: 'REQUEST_FAILED',
      retryAfter: '2'
    });
  });
});
