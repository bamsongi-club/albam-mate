package cloud.bamsongi.albammate.game.repository;

import java.util.*;

import org.springframework.data.jpa.repository.*;

import cloud.bamsongi.albammate.game.entity.GameTheme;

public interface GameThemeRepository extends JpaRepository<GameTheme, Long> {
	long countByCodeIn(Collection<String> codes);

	@Query("select new cloud.bamsongi.albammate.game.repository.GameThemeOptionRow(t.code,t.nameKo,t.nameEn) from GameTheme t order by t.nameKo, t.code")
	List<GameThemeOptionRow> findOptions();
}
