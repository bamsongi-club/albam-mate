package cloud.bamsongi.albammate.game.repository;

import java.math.BigDecimal;

/** 게임 목록 조립에 필요한 열만 조회하는 내부 projection이다. */
public record GameListRow(
        Long id,
        Long bggId,
        String name,
        String englishName,
        String imageUrl,
        String supportedPlayerCount,
        String tag,
        String estimatedPlayTime,
        BigDecimal complexity) {}
