package cloud.bamsongi.albammate.matching.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.matching.entity.MatchBlock;

public interface MatchBlockRepository extends JpaRepository<MatchBlock, Long> {

	Page<MatchBlock> findByBlockerUserIdOrderByCreatedAtDescIdDesc(Long blockerUserId, Pageable pageable);

	Optional<MatchBlock> findByBlockerUserIdAndBlockedUserId(Long blockerUserId, Long blockedUserId);

	long deleteByIdAndBlockerUserId(Long id, Long blockerUserId);

	@Query("""
		select case when count(matchBlock) > 0 then true else false end
		from MatchBlock matchBlock
		where (matchBlock.blockerUserId = :firstUserId and matchBlock.blockedUserId = :secondUserId)
		   or (matchBlock.blockerUserId = :secondUserId and matchBlock.blockedUserId = :firstUserId)
		""")
	boolean existsBlockBetweenUsers(
		@Param("firstUserId")
		Long firstUserId, @Param("secondUserId")
		Long secondUserId);
}
