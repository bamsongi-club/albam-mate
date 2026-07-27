package cloud.bamsongi.albammate.game.repository;

import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.game.entity.Game;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GameRepository extends JpaRepository<Game, Long> {

    Page<Game> findByNameContainingIgnoreCase(String keyword, Pageable pageable);

    @Query(
            """
            select new cloud.bamsongi.albammate.game.contract.GameSummary(g.id, g.bggId, g.name)
            from Game g
            where g.id = :gameId
            """)
    Optional<GameSummary> findSummaryById(@Param("gameId") Long gameId);
}
