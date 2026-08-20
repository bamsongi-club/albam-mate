export function hasExpectedRoomId(data, expectedRoomId) {
  return data
    && Number.isInteger(data.roomId)
    && data.roomId === expectedRoomId;
}

export function hasParticipationPayload(
  data,
  expectedRoomId,
  expectedParticipationStatus,
  expectedRoomStatus,
  expectedParticipantCount,
  expectedRemainingSeats,
) {
  return hasExpectedRoomId(data, expectedRoomId)
    && data.participationStatus === expectedParticipationStatus
    && data.roomStatus === expectedRoomStatus
    && data.participantCount === expectedParticipantCount
    && data.remainingRecruitmentSeats === expectedRemainingSeats;
}

export function hasWaitlistPayload(data, expectedRoomId, expectedPosition = null) {
  return hasExpectedRoomId(data, expectedRoomId)
    && data.waitlistStatus === 'WAITING'
    && Number.isInteger(data.position)
    && data.position > 0
    && (expectedPosition === null || data.position === expectedPosition);
}

export function hasNicknameOnlySet(participants, expectedNicknames) {
  if (!Array.isArray(participants)
    || !Array.isArray(expectedNicknames)
    || participants.length !== expectedNicknames.length) {
    return false;
  }

  const expectedNicknameSet = new Set(expectedNicknames);
  if (expectedNicknameSet.size !== expectedNicknames.length) {
    return false;
  }

  const actualNicknameSet = new Set();
  for (const participant of participants) {
    const nicknameOnly = participant
      && typeof participant === 'object'
      && !Array.isArray(participant)
      && typeof participant.nickname === 'string'
      && Object.keys(participant).length === 1;
    if (!nicknameOnly || !expectedNicknameSet.has(participant.nickname)) {
      return false;
    }
    actualNicknameSet.add(participant.nickname);
  }
  return actualNicknameSet.size === expectedNicknameSet.size;
}

function hasT3PromotionPayload(data, room) {
  return data.roomStatus === 'CLOSED'
    && data.participantCount === room.capacity + 1
    && data.remainingRecruitmentSeats === 0;
}

function hasT3VacancyPayload(data, room) {
  return data.roomStatus === 'RECRUITING'
    && data.participantCount === room.capacity
    && data.remainingRecruitmentSeats === 1;
}

export function hasT3CancelPayload(data, room, t3Mode) {
  if (!room || !hasExpectedRoomId(data, room.id) || data.participationStatus !== 'CANCELED') {
    return false;
  }
  if (t3Mode === 'wait-first') {
    return hasT3PromotionPayload(data, room);
  }
  if (t3Mode === 'cancel-first') {
    return hasT3VacancyPayload(data, room);
  }
  if (t3Mode === 'race') {
    return hasT3PromotionPayload(data, room) || hasT3VacancyPayload(data, room);
  }
  return false;
}
