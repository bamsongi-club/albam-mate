package cloud.bamsongi.albammate.game.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import cloud.bamsongi.albammate.game.entity.GameCategoryRelation;
import cloud.bamsongi.albammate.game.entity.GameCategoryRelationId;

public interface GameCategoryRelationRepository extends JpaRepository<GameCategoryRelation, GameCategoryRelationId> {
	@Query("""
		select new cloud.bamsongi.albammate.game.repository.GameCategorySummaryRow(
			r.category.code, r.category.nameKo, r.category.nameEn)
		from GameCategoryRelation r
		where r.game.id in :gameIds
		order by r.game.id, r.category.displayOrder
		""")
	List<GameCategorySummaryRow> findSummariesByGameIdIn(Collection<Long> gameIds);
}
