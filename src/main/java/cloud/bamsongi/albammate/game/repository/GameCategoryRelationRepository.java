package cloud.bamsongi.albammate.game.repository;

import java.util.*;

import org.springframework.data.jpa.repository.*;

import cloud.bamsongi.albammate.game.entity.*;

public interface GameCategoryRelationRepository extends JpaRepository<GameCategoryRelation, GameCategoryRelationId> {
	@Query("select new cloud.bamsongi.albammate.game.repository.GameCategorySummaryRow(r.game.id,r.category.code,r.category.nameKo,r.category.nameEn) from GameCategoryRelation r where r.game.id in :gameIds order by r.game.id,r.category.displayOrder")
	List<GameCategorySummaryRow> findSummariesByGameIdIn(Collection<Long> gameIds);
}
