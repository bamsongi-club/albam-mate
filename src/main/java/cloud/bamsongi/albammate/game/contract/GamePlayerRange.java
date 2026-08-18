package cloud.bamsongi.albammate.game.contract;

/** MATCH가 게임 지원 인원 범위를 판정할 때 사용하는 전용 값이다. */
public record GamePlayerRange(long gameId, int minPlayers, int maxPlayers) {
}
