package cloud.bamsongi.albammate.game.dto;

import cloud.bamsongi.albammate.game.entity.Game;
import java.math.BigDecimal;

public record GameDetail(
        Long id,
        Long bggId,
        String name,
        String englishName,
        String imageUrl,
        String supportedPlayerCount,
        String tag,
        String estimatedPlayTime,
        BigDecimal complexity,
        long upcomingRoomCount,
        String alias,
        String description,
        String detailDescription) {

    /**
     * 게임 엔티티와 예정 모임 수로 상세 응답을 생성한다.
     *
     * @param game 상세 정보를 담은 게임 엔티티
     * @param upcomingRoomCount 조회 시각 기준 예정 모임 수
     * @return 예정 모임 수가 포함된 게임 상세 응답
     */
    public static GameDetail from(Game game, long upcomingRoomCount) {
        return new GameDetail(
                game.getId(),
                game.getBggId(),
                game.getName(),
                game.getEnglishName(),
                game.getImageUrl(),
                game.getSupportedPlayerCount(),
                game.getTag(),
                game.getEstimatedPlayTime(),
                game.getComplexity(),
                upcomingRoomCount,
                game.getAlias(),
                game.getDescription(),
                game.getDetailDescription());
    }
}
