package cloud.bamsongi.albammate.game.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import cloud.bamsongi.albammate.game.entity.GameMechanism;

public interface GameMechanismRepository extends JpaRepository<GameMechanism, Long> {

	long countByCodeInAndIsPublicTrue(Collection<String> codes);

	@Query("""
		select new cloud.bamsongi.albammate.game.repository.GameMechanismOptionRow(
			m.code, m.nameKo, m.nameEn, m.featuredOrder)
		from GameMechanism m
		where m.isPublic = true
		order by case when m.featuredOrder is null then 1 else 0 end,
			m.featuredOrder asc, m.nameKo asc, m.code asc
		""")
	List<GameMechanismOptionRow> findPublicOptions();
}
