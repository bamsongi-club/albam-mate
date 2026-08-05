package cloud.bamsongi.albammate.game.repository;

import java.util.*;

import org.springframework.data.jpa.repository.*;

import cloud.bamsongi.albammate.game.entity.GameCategory;

public interface GameCategoryRepository extends JpaRepository<GameCategory, Long> {
	long countByCodeIn(Collection<String> codes);

	@Query("select new cloud.bamsongi.albammate.game.repository.GameCategoryOptionRow(c.code,c.nameKo,c.nameEn,c.displayOrder) from GameCategory c order by c.displayOrder")
	List<GameCategoryOptionRow> findOptions();
}
