const API_BASE_PATH = (import.meta.env.VITE_API_BASE_PATH || '').replace(/\/$/, '');

export class ApiError extends Error {
  constructor({ status, code, message, retryAfter }) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.retryAfter = retryAfter;
  }
}

let csrfToken;
let csrfTokenRequest;
let unauthenticatedHandler;
let authenticationGeneration = 0;

function endpoint(path) {
  return API_BASE_PATH + path;
}

async function parsePayload(response) {
  const contentType = response.headers.get('content-type') || '';
  if (!contentType.includes('application/json')) return null;
  return response.json();
}

function staleAuthenticationError() {
  const error = new Error('인증 상태가 변경되어 이전 응답을 무시합니다.');
  error.name = 'AbortError';
  return error;
}

async function request(path, { method = 'GET', body, headers, signal } = {}) {
  const requestAuthenticationGeneration = authenticationGeneration;
  let response;
  let payload;
  try {
    response = await fetch(endpoint(path), {
      method,
      credentials: 'include',
      signal,
      headers: {
        Accept: 'application/json',
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...headers
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) })
    });
    payload = await parsePayload(response);
  } catch (error) {
    if (requestAuthenticationGeneration !== authenticationGeneration) {
      throw staleAuthenticationError();
    }
    throw error;
  }

  if (requestAuthenticationGeneration !== authenticationGeneration) {
    throw staleAuthenticationError();
  }

  if (!response.ok) {
    const error = new ApiError({
      status: response.status,
      code: payload?.code || 'REQUEST_FAILED',
      message: payload?.message || '요청을 처리하지 못했어요. 잠시 후 다시 시도해주세요.',
      retryAfter: response.headers.get('retry-after')
    });
    if (response.status === 401) {
      clearCsrfToken();
      unauthenticatedHandler?.();
    }
    throw error;
  }

  if (!payload || payload.status !== response.status || !Object.hasOwn(payload, 'data')) {
    throw new ApiError({
      status: response.status,
      code: 'INVALID_API_RESPONSE',
      message: '서버 응답 형식을 확인하지 못했어요.'
    });
  }

  return payload.data;
}

async function getCsrfToken() {
  if (csrfToken) return csrfToken;
  if (!csrfTokenRequest) {
    csrfTokenRequest = request('/api/auth/csrf')
      .then((token) => {
        csrfToken = token;
        return token;
      })
      .finally(() => {
        csrfTokenRequest = undefined;
      });
  }
  return csrfTokenRequest;
}

async function mutate(path, options = {}) {
  const token = await getCsrfToken();
  return request(path, {
    ...options,
    headers: {
      ...options.headers,
      [token.headerName]: token.token
    }
  });
}

// 경로의 제공자 값은 SocialProvider의 소문자 표기다.
function socialProviderPath(provider) {
  return String(provider).toLowerCase();
}

export function socialLoginUrl(provider) {
  return endpoint('/api/auth/social/authorization/' + socialProviderPath(provider));
}

export function clearCsrfToken() {
  csrfToken = undefined;
}

function advanceAuthenticationGeneration() {
  authenticationGeneration += 1;
  clearCsrfToken();
}

export function setUnauthenticatedHandler(handler) {
  unauthenticatedHandler = handler;
}

function query(parameters) {
  const search = new URLSearchParams();
  const append = (key, value) => {
    if (value !== undefined && value !== null && value !== '') search.append(key, String(value));
  };
  // 배열은 같은 이름을 반복해 전달한다. 빈 배열은 조건 없음과 같아 아무것도 붙이지 않는다.
  Object.entries(parameters).forEach(([key, value]) => {
    if (Array.isArray(value)) value.forEach((item) => append(key, item));
    else append(key, value);
  });
  const value = search.toString();
  return value ? '?' + value : '';
}

export const api = {
  getMyProfile: () => request('/api/users/me'),
  getSocialProviders: (signal) => request('/api/auth/social/providers', { signal }),
  // 서버는 same-site authorization 경로만 돌려주며 실제 이동은 호출자가 전체 페이지 이동으로 수행한다.
  startSocialLink: async (provider) => {
    const { authorizationUri } = await mutate(
      '/api/users/me/social-accounts/' + socialProviderPath(provider) + '/link',
      { method: 'POST' }
    );
    return endpoint(authorizationUri);
  },
  getGame: (gameId, signal) => request('/api/games/' + gameId, { signal }),
  getGames: ({ keyword, upcomingOnly, playerCount, playTime, complexityMin, complexityMax, page = 0, size = 10 }, signal) =>
    request('/api/games' + query({ keyword, upcomingOnly, playerCount, playTime, complexityMin, complexityMax, page, size }), { signal }),
  getRoom: (roomId, signal) => request('/api/rooms/' + roomId, { signal }),
  getRooms: (
    { type, gameId, keyword, startsAtFrom, startsAtTo, minRemainingSeats, experienceLevels, rulemasterOnly, page = 0, size = 10 },
    signal
  ) =>
    request('/api/rooms' + query({
      type,
      gameId,
      keyword,
      startsAtFrom,
      startsAtTo,
      minRemainingSeats,
      experienceLevels,
      // 룰마스터 진행 여부는 조건으로 쓸 때만 보낸다. false는 조건 없음과 같다.
      rulemasterOnly: rulemasterOnly ? 'true' : undefined,
      page,
      size
    }), { signal }),
  getMyRooms: ({ role, page = 0, size = 10 }, signal) =>
    request('/api/users/me/rooms' + query({ role, page, size }), { signal }),
  getChatMessages: (roomId, optionsOrSignal = {}, maybeSignal) => {
    const signal = optionsOrSignal?.aborted !== undefined ? optionsOrSignal : maybeSignal;
    const options = signal ? {} : optionsOrSignal;
    return request('/api/rooms/' + roomId + '/chat/messages' + query(options), { signal });
  },
  sendChatMessage: (roomId, message) => mutate('/api/rooms/' + roomId + '/chat/messages', { method: 'POST', body: message }),
  getNotifications: ({ page = 0, size = 10 } = {}, signal) =>
    request('/api/users/me/notifications' + query({ page, size }), { signal }),
  getUnreadNotificationCount: (signal) =>
    request('/api/users/me/notifications/unread-count', { signal }),
  markNotificationRead: (notificationId) => mutate(
    '/api/users/me/notifications/' + notificationId,
    { method: 'PATCH', body: { read: true } }
  ),
  markAllNotificationsRead: () => mutate(
    '/api/users/me/notifications',
    { method: 'PATCH', body: { read: true } }
  ),
  signup: async (credentials) => mutate('/api/auth/signup', { method: 'POST', body: credentials }),
  login: async (credentials) => {
    const user = await mutate('/api/auth/login', { method: 'POST', body: credentials });
    advanceAuthenticationGeneration();
    return user;
  },
  logout: async () => {
    try {
      const result = await mutate('/api/auth/logout', { method: 'POST' });
      advanceAuthenticationGeneration();
      return result;
    } finally {
      clearCsrfToken();
    }
  },
  updateMyProfile: (profile) => mutate('/api/users/me', { method: 'PATCH', body: profile }),
  createRoom: (room) => mutate('/api/rooms', { method: 'POST', body: room }),
  updateRoom: (roomId, room) => mutate('/api/rooms/' + roomId, { method: 'PATCH', body: room }),
  cancelRoom: (roomId) => mutate('/api/rooms/' + roomId, { method: 'DELETE' }),
  finishRoom: (roomId) => mutate('/api/rooms/' + roomId + '/status', { method: 'PATCH', body: { status: 'FINISHED' } }),
  participate: (roomId) => mutate('/api/rooms/' + roomId + '/participants', { method: 'POST' }),
  cancelParticipation: (roomId) => mutate('/api/rooms/' + roomId + '/participants/me', { method: 'DELETE' })
};

export function messageForError(error, fallback = '요청을 처리하지 못했어요.') {
  if (error instanceof ApiError) {
    if (error.code === 'RATE_LIMIT_EXCEEDED' && error.retryAfter) {
      return error.retryAfter + '초 뒤에 다시 시도해주세요.';
    }
    return error.message || fallback;
  }
  return fallback;
}
