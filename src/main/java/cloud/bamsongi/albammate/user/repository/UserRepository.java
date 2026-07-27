package cloud.bamsongi.albammate.user.repository;

import cloud.bamsongi.albammate.user.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    @Query(
            """
            select u.nickname
            from User u
            where u.id = :userId
            """)
    Optional<String> findNicknameById(@Param("userId") Long userId);
}
