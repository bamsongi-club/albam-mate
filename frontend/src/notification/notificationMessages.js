const NOTIFICATION_MESSAGES = {
  PARTICIPANT_JOINED: (roomTitle) => `'${roomTitle}' 모임에 새 참가자가 있어요.`,
  PARTICIPANT_CANCELED: (roomTitle) => `'${roomTitle}' 모임에 빈자리가 생겼어요.`,
  ROOM_CANCELED: (roomTitle) => `'${roomTitle}' 모임이 취소됐어요.`
};

export function notificationMessage({ type, roomTitle }) {
  const createMessage = NOTIFICATION_MESSAGES[type];
  if (!createMessage) return '새로운 알림이 있어요.';
  return createMessage(String(roomTitle ?? ''));
}
