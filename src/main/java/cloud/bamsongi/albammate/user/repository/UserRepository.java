package cloud.bamsongi.albammate.user.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.user.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

	boolean existsByEmail(String email);

	Optional<User> findByEmail(String email);

	Optional<User> findByEmailAndPasswordHashIsNotNull(String email);

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
