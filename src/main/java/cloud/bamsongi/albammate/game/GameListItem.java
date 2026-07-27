package cloud.bamsongi.albammate.game;

import java.math.BigDecimal;

public record GameListItem(
        Long id,
        Long bggId,
        String name,
        String englishName,
        String imageUrl,
        String recommendedPlayerCount,
        String tag,
        String estimatedPlayTime,
        BigDecimal complexity,
        long upcomingRoomCount) {}
