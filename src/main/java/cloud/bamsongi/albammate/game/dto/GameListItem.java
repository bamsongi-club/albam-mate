package cloud.bamsongi.albammate.game.dto;

import java.math.BigDecimal;

public record GameListItem(
        Long id,
        Long bggId,
        String name,
        String englishName,
        String imageUrl,
        String supportedPlayerCount,
        String tag,
        String estimatedPlayTime,
        BigDecimal complexity,
        long upcomingRoomCount) {}
