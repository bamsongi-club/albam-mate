package cloud.bamsongi.albammate.room.repository;

import cloud.bamsongi.albammate.room.entity.Participation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParticipationRepository extends JpaRepository<Participation, Long> {

    Optional<Participation> findByRoomIdAndUserId(Long roomId, Long userId);
}
