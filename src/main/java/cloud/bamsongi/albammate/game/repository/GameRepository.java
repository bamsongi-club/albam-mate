package cloud.bamsongi.albammate.game.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.game.entity.Game;

public interface GameRepository extends JpaRepository<Game, Long>, JpaSpecificationExecutor<Game> {

	default Slice<GameSummary> findCandidateSummaries(Specification<Game> specification, Pageable pageable) {
		return findBy(specification, query -> query.as(GameSummary.class)
			.sortBy(Sort.by(Sort.Order.asc("id")))
			.slice(pageable));
	}

	default Slice<GameSummary> findLexicalFallbackSummaries(Specification<Game> specification, Pageable pageable) {
		Pageable fallbackPageable = PageRequest.of(
			pageable.getPageNumber(), pageable.getPageSize(), Sort.by(
				Sort.Order.desc("popularityScore"),
				Sort.Order.asc("name"),
				Sort.Order.asc("id")));
		return findBy(specification, query -> query.as(GameSummary.class)
			.slice(fallbackPageable));
	}

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
