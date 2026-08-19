package cloud.bamsongi.albammate.matching.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.matching.entity.MatchBlock;

public interface MatchBlockRepository extends JpaRepository<MatchBlock, Long> {

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
