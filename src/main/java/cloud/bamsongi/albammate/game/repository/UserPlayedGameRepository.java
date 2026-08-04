package cloud.bamsongi.albammate.game.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.entity.UserPlayedGame;

public interface UserPlayedGameRepository extends JpaRepository<UserPlayedGame, Long> {

	boolean existsByUserIdAndGameId(Long userId, Long gameId);

	List<UserPlayedGame> findByUserIdAndGameId(Long userId, Long gameId);

	@Transactional
	long deleteByUserIdAndGameId(Long userId, Long gameId);

	@Query("""
		select relation.gameId
		from UserPlayedGame relation
		where relation.userId = :userId
		  and relation.gameId in :gameIds
		""")
	List<Long> findGameIdsByUserIdAndGameIdIn(@Param("userId")
	Long userId, @Param("gameIds")
	Collection<Long> gameIds);
}
