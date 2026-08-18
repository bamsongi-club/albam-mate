package cloud.bamsongi.albammate.game.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.game.entity.Game;

public interface GameRepository extends JpaRepository<Game, Long>, JpaSpecificationExecutor<Game> {

	@Query("""
		select new cloud.bamsongi.albammate.game.contract.GameSummary(g.id, g.bggId, g.name)
		from Game g
		where g.id = :gameId
		""")
	Optional<GameSummary> findSummaryById(@Param("gameId")
	Long gameId);

	@Query("""
		select new cloud.bamsongi.albammate.game.contract.GameSummary(g.id, g.bggId, g.name)
		from Game g
		where g.id in :gameIds
		""")
	List<GameSummary> findSummariesByIds(@Param("gameIds")
	Collection<Long> gameIds);
}
