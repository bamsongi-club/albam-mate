import assert from 'node:assert/strict';
import test from 'node:test';

import {
  hasExpectedRoomId,
  hasNicknameOnlySet,
  hasParticipationPayload,
  hasT3CancelPayload,
  hasWaitlistPayload,
} from '../lib/write-response-contract.mjs';

test('참가 응답은 요청한 ROOM의 성공 payload만 허용한다', () => {
  const expectedRoomId = 101;
  const payload = {
    roomId: expectedRoomId,
    participationStatus: 'ACTIVE',
    roomStatus: 'CLOSED',
    participantCount: 2,
    remainingRecruitmentSeats: 0,
  };

  assert.equal(hasParticipationPayload(payload, expectedRoomId, 'ACTIVE', 'CLOSED', 2, 0), true);
  assert.equal(hasParticipationPayload({ ...payload, roomId: 102 }, expectedRoomId, 'ACTIVE', 'CLOSED', 2, 0), false);
  assert.equal(hasParticipationPayload({ ...payload, roomId: undefined }, expectedRoomId, 'ACTIVE', 'CLOSED', 2, 0), false);
});

test('대기 응답은 요청 ROOM과 기대 순번을 함께 확인한다', () => {
  const expectedRoomId = 201;
  const waitlist = { roomId: expectedRoomId, waitlistStatus: 'WAITING', position: 1 };

  assert.equal(hasWaitlistPayload(waitlist, expectedRoomId, 1), true);
  assert.equal(hasWaitlistPayload({ ...waitlist, roomId: 202 }, expectedRoomId), false);
  assert.equal(hasWaitlistPayload({ ...waitlist, position: 2 }, expectedRoomId, 1), false);
});

test('T3 취소 응답은 mode별 최종 ROOM 상태와 인원을 확인한다', () => {
  const room = { id: 301, capacity: 1 };
  const promoted = {
    roomId: room.id,
    participationStatus: 'CANCELED',
    roomStatus: 'CLOSED',
    participantCount: 2,
    remainingRecruitmentSeats: 0,
  };
  const reopened = {
    roomId: room.id,
    participationStatus: 'CANCELED',
    roomStatus: 'RECRUITING',
    participantCount: 1,
    remainingRecruitmentSeats: 1,
  };

  assert.equal(hasT3CancelPayload(promoted, room, 'wait-first'), true);
  assert.equal(hasT3CancelPayload(reopened, room, 'cancel-first'), true);
  assert.equal(hasT3CancelPayload(promoted, room, 'race'), true);
  assert.equal(hasT3CancelPayload(reopened, room, 'race'), true);
  assert.equal(hasT3CancelPayload(reopened, room, 'wait-first'), false);
  assert.equal(hasT3CancelPayload(promoted, room, 'cancel-first'), false);
  assert.equal(hasT3CancelPayload({ ...promoted, participantCount: 1 }, room, 'race'), false);
  assert.equal(hasT3CancelPayload({ ...promoted, roomId: 302 }, room, 'race'), false);
});

test('취소 응답은 다른 ROOM ID를 성공으로 분류하지 않는다', () => {
  const expectedRoomId = 401;
  const canceled = { roomId: expectedRoomId, participationStatus: 'CANCELED' };

  assert.equal(hasExpectedRoomId(canceled, expectedRoomId), true);
  assert.equal(hasExpectedRoomId({ ...canceled, roomId: 202 }, expectedRoomId), false);
  assert.equal(hasExpectedRoomId({ ...canceled, roomId: null }, expectedRoomId), false);
});

test('T5 참가자 응답은 순서와 무관한 nickname-only 집합을 검증한다', () => {
  const expectedNicknames = ['host', 'participant-a', 'participant-b'];
  const reversed = [
    { nickname: 'participant-b' },
    { nickname: 'host' },
    { nickname: 'participant-a' },
  ];

  assert.equal(hasNicknameOnlySet(reversed, expectedNicknames), true);
  assert.equal(
    hasNicknameOnlySet([
      { nickname: 'host' },
      { nickname: 'participant-a' },
      { nickname: 'participant-a' },
    ], expectedNicknames),
    false,
  );
  assert.equal(
    hasNicknameOnlySet([
      { nickname: 'host', userId: 1 },
      { nickname: 'participant-a' },
      { nickname: 'participant-b' },
    ], expectedNicknames),
    false,
  );
});
