export const UNAVAILABLE_NOTIFICATION_ROOM_MESSAGE = '현재 확인할 수 없는 모임입니다.';

export async function navigateToNotificationRoom({
  notification,
  getRoom,
  navigate,
  isUnauthenticated,
  onUnauthenticated,
  onUnavailable
}) {
  try {
    await getRoom(notification.roomId);
    navigate('/session/' + notification.roomId);
    return true;
  } catch (error) {
    if (error?.name === 'AbortError') return false;
    if (isUnauthenticated(error)) onUnauthenticated();
    else onUnavailable(UNAVAILABLE_NOTIFICATION_ROOM_MESSAGE);
    return false;
  }
}

export function selectNotificationAndNavigate({
  notification,
  markAsRead,
  getRoom,
  navigate,
  isUnauthenticated,
  onUnauthenticated,
  onUnavailable
}) {
  markAsRead(notification);
  return navigateToNotificationRoom({
    notification,
    getRoom,
    navigate,
    isUnauthenticated,
    onUnauthenticated,
    onUnavailable
  });
}
