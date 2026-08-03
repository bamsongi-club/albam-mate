import { describe, expect, it } from 'vitest';
import { notificationMessage } from './notificationMessages';

describe('T5 알림 표시 문구', () => {
  it.each([
    ['PARTICIPANT_JOINED', "'저녁 전략 모임' 모임에 새 참가자가 있어요."],
    ['PARTICIPANT_CANCELED', "'저녁 전략 모임' 모임에 빈자리가 생겼어요."],
    ['ROOM_CANCELED', "'저녁 전략 모임' 모임이 취소됐어요."]
  ])('%s 유형을 정본 문구로 변환한다', (type, expected) => {
    expect(notificationMessage({ type, roomTitle: '저녁 전략 모임' })).toBe(expected);
  });
});
