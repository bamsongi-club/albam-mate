package cloud.bamsongi.albammate.game.dto;

import java.math.BigDecimal;

public record GameDetail(
        Long id,
        Long bggId,
        String name,
        String englishName,
        String imageUrl,
        String recommendedPlayerCount,
        String tag,
        String estimatedPlayTime,
        BigDecimal complexity,
        long upcomingRoomCount,
        String alias,
        String description,
        String detailDescription) {}
