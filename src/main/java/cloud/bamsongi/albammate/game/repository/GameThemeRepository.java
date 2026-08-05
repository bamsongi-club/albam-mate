package cloud.bamsongi.albammate.game.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import cloud.bamsongi.albammate.game.entity.GameTheme;

public interface GameThemeRepository extends JpaRepository<GameTheme, Long> {
	long countByCodeIn(Collection<String> codes);

	@Query("select new cloud.bamsongi.albammate.game.repository.GameThemeOptionRow(t.code,t.nameKo,t.nameEn) from GameTheme t order by t.nameKo, t.code")
	List<GameThemeOptionRow> findOptions();
}
