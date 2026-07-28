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

async function request(path, { method = 'GET', body, headers, signal } = {}) {
  const requestAuthenticationGeneration = authenticationGeneration;
  const response = await fetch(endpoint(path), {
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
  const payload = await parsePayload(response);

  if (!response.ok) {
    const error = new ApiError({
      status: response.status,
      code: payload?.code || 'REQUEST_FAILED',
      message: payload?.message || '요청을 처리하지 못했어요. 잠시 후 다시 시도해주세요.',
      retryAfter: response.headers.get('retry-after')
    });
    if (
      response.status === 401
      && error.code === 'UNAUTHENTICATED'
      && requestAuthenticationGeneration === authenticationGeneration
    ) {
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
  Object.entries(parameters).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') search.set(key, String(value));
  });
  const value = search.toString();
  return value ? '?' + value : '';
}

export const api = {
  getMyProfile: () => request('/api/users/me'),
  getGame: (gameId, signal) => request('/api/games/' + gameId, { signal }),
  getGames: ({ keyword, page = 0, size = 10 }, signal) =>
    request('/api/games' + query({ keyword, page, size }), { signal }),
  getRoom: (roomId, signal) => request('/api/rooms/' + roomId, { signal }),
  getRooms: ({ type, gameId, keyword, page = 0, size = 10 }, signal) =>
    request('/api/rooms' + query({ type, gameId, keyword, page, size }), { signal }),
  getMyRooms: ({ role, page = 0, size = 10 }, signal) =>
    request('/api/users/me/rooms' + query({ role, page, size }), { signal }),
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
