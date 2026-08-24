package cloud.bamsongi.albammate.game.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import cloud.bamsongi.albammate.game.entity.GameMechanismRelation;
import cloud.bamsongi.albammate.game.entity.GameMechanismRelationId;

public interface GameMechanismRelationRepository
	extends JpaRepository<GameMechanismRelation, GameMechanismRelationId> {

	@Query("""
		select new cloud.bamsongi.albammate.game.repository.GameMechanismSummaryRow(
			m.code, m.nameKo, m.nameEn)
		from GameMechanismRelation relation
		join relation.mechanism m
		where relation.game.id = :gameId
			and m.isPublic = true
		order by m.nameKo asc, m.code asc
		""")
	List<GameMechanismSummaryRow> findPublicSummariesByGameId(Long gameId);
}
