package cloud.bamsongi.albammate.game.dto;

import java.util.List;

public record GameRankingResponse(List<GameRankingItem> overall, List<GameRankingItem> upcomingWeek) {
}
