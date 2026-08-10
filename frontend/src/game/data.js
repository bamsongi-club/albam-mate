export function normalizeGameSummary(game) {
  const complexity = Number(game.complexity);
  return {
    id: String(game.id),
    title: game.name || '이름 없는 게임',
    englishName: game.englishName || '',
    imageUrl: game.imageUrl || null,
    players: game.supportedPlayerCount || '',
    time: game.estimatedPlayTime || '',
    complexity: Number.isFinite(complexity) ? complexity.toFixed(1) : '',
    tag: game.tag || '',
    upcomingRoomCount: Number(game.upcomingRoomCount || 0),
    alias: game.alias || null,
    // 값이 없으면 추정하거나 대체값을 넣지 않는다. 화면에서도 그 자리를 비운다.
    minAge: Number.isFinite(Number(game.minAge)) && game.minAge !== null ? Number(game.minAge) : null,
    description: game.description || '',
    detailDescription: game.detailDescription || '',
    // 비로그인 응답의 `null`은 관계 없음이 아니라 아직 판정하지 않은 상태다. 그대로 둔다.
    playedByMe: game.playedByMe ?? null
  };
}

export function normalizeRoom(room) {
  return {
    ...room,
    id: String(room.id),
    game: room.game ? normalizeGameSummary(room.game) : null,
    participantCount: Number(room.participantCount || 0),
    remainingRecruitmentSeats: Number(room.remainingRecruitmentSeats || 0),
    recruitmentCapacity: Number(room.recruitmentCapacity || 0),
    participants: room.participants || []
  };
}

export function gameMeta(game) {
  return [
    game.players,
    game.time,
    game.complexity ? '난이도 ' + game.complexity : '',
    game.minAge ? game.minAge + '세 이상' : ''
  ].filter(Boolean).join(' · ');
}
