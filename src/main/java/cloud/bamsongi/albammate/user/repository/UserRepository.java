package cloud.bamsongi.albammate.user.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.user.entity.User;
import jakarta.persistence.LockModeType;

public interface UserRepository extends JpaRepository<User, Long> {

	boolean existsByEmail(String email);

	Optional<User> findByEmail(String email);

	Optional<User> findByEmailAndPasswordHashIsNotNull(String email);

	/**
	 * 프로필 이미지 교체처럼 "이전 값을 읽고 새 값으로 바꾼 뒤 이전 파일을 지우는" 흐름에서 동시 요청이 같은 사용자
	 * 행을 함께 갱신하면 한쪽이 남긴 파일의 URL이 어느 트랜잭션에도 기록되지 않아 고아 파일로 남는다. 행 잠금으로 같은
	 * 사용자에 대한 프로필 이미지 변경을 직렬화해 이 경쟁을 없앤다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.id = :userId")
	Optional<User> findByIdForUpdate(@Param("userId")
	Long userId);

	@Query("""
		select u.nickname
		from User u
		where u.id = :userId
		""")
	Optional<String> findNicknameById(@Param("userId")
	Long userId);

	@Query("""
		select u.id as id, u.nickname as nickname
		from User u
		where u.id in :userIds
		""")
	List<UserNicknameProjection> findNicknameProjectionsByIds(@Param("userIds")
	Collection<Long> userIds);

	interface UserNicknameProjection {

		Long getId();

		String getNickname();
	}
}
