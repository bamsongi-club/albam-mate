package cloud.bamsongi.albammate.game.repository;

import java.util.*;

import org.springframework.data.jpa.repository.*;

import cloud.bamsongi.albammate.game.entity.*;

public interface GameThemeRelationRepository extends JpaRepository<GameThemeRelation, GameThemeRelationId> {
	@Query("select new cloud.bamsongi.albammate.game.repository.GameThemeSummaryRow(r.game.id,r.theme.code,r.theme.nameKo,r.theme.nameEn) from GameThemeRelation r where r.game.id in :gameIds order by r.game.id,r.theme.nameKo,r.theme.code")
	List<GameThemeSummaryRow> findSummariesByGameIdIn(Collection<Long> gameIds);
}
