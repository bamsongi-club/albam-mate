package cloud.bamsongi.albammate.room.repository;

import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParticipationRepository extends JpaRepository<Participation, Long> {

    boolean existsByRoom_IdAndStatusAndUserIdNot(
            Long roomId, ParticipationStatus status, Long excludedUserId);
}
