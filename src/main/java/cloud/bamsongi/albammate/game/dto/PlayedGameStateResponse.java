package cloud.bamsongi.albammate.game.dto;

/** 현재 사용자의 게임 표시 요청이 확정한 목표 상태다. */
public record PlayedGameStateResponse(Long gameId, boolean playedByMe) {
}
