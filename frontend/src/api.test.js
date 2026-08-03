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
});
