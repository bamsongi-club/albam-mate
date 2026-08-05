package cloud.bamsongi.albammate.game.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import cloud.bamsongi.albammate.game.entity.GameThemeRelation;
import cloud.bamsongi.albammate.game.entity.GameThemeRelationId;

public interface GameThemeRelationRepository extends JpaRepository<GameThemeRelation, GameThemeRelationId> {
	@Query("select new cloud.bamsongi.albammate.game.repository.GameThemeSummaryRow(r.game.id,r.theme.code,r.theme.nameKo,r.theme.nameEn) from GameThemeRelation r where r.game.id in :gameIds order by r.game.id,r.theme.nameKo,r.theme.code")
	List<GameThemeSummaryRow> findSummariesByGameIdIn(Collection<Long> gameIds);
}
