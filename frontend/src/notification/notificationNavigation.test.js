import { describe, expect, it, vi } from 'vitest';
import {
  navigateToNotificationRoom,
  UNAVAILABLE_NOTIFICATION_ROOM_MESSAGE
} from './notificationNavigation';

function navigationOptions(overrides = {}) {
  return {
    notification: { roomId: 42 },
    getRoom: vi.fn().mockResolvedValue({ id: 42 }),
    navigate: vi.fn(),
    isUnauthenticated: vi.fn().mockReturnValue(false),
    onUnauthenticated: vi.fn(),
    onUnavailable: vi.fn(),
    ...overrides
  };
}

describe('T6 알림에서 방 이동', () => {
  it('현재 방 상세 권한을 확인한 뒤에만 이동한다', async () => {
    const options = navigationOptions();

    await expect(navigateToNotificationRoom(options)).resolves.toBe(true);

    expect(options.getRoom).toHaveBeenCalledWith(42);
    expect(options.navigate).toHaveBeenCalledWith('/session/42');
    expect(options.onUnavailable).not.toHaveBeenCalled();
  });

  it('방을 확인할 수 없으면 이동하지 않고 계약된 안내를 표시한다', async () => {
    const options = navigationOptions({ getRoom: vi.fn().mockRejectedValue(new Error('not found')) });

    await expect(navigateToNotificationRoom(options)).resolves.toBe(false);

    expect(options.navigate).not.toHaveBeenCalled();
    expect(options.onUnavailable).toHaveBeenCalledWith(UNAVAILABLE_NOTIFICATION_ROOM_MESSAGE);
  });

  it('401은 공통 세션 만료 흐름으로 넘긴다', async () => {
    const error = new Error('expired');
    const options = navigationOptions({
      getRoom: vi.fn().mockRejectedValue(error),
      isUnauthenticated: vi.fn().mockReturnValue(true)
    });

    await navigateToNotificationRoom(options);

    expect(options.isUnauthenticated).toHaveBeenCalledWith(error);
    expect(options.onUnauthenticated).toHaveBeenCalledOnce();
    expect(options.onUnavailable).not.toHaveBeenCalled();
  });

  it('사용자 전환으로 폐기된 방 응답은 이동이나 오류 안내에 사용하지 않는다', async () => {
    const staleError = new Error('stale');
    staleError.name = 'AbortError';
    const options = navigationOptions({ getRoom: vi.fn().mockRejectedValue(staleError) });

    await expect(navigateToNotificationRoom(options)).resolves.toBe(false);

    expect(options.navigate).not.toHaveBeenCalled();
    expect(options.onUnauthenticated).not.toHaveBeenCalled();
    expect(options.onUnavailable).not.toHaveBeenCalled();
  });
});
