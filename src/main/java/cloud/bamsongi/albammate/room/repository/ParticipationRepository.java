package cloud.bamsongi.albammate.room.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;

public interface ParticipationRepository extends JpaRepository<Participation, Long> {

	Optional<Participation> findByRoomIdAndUserId(Long roomId, Long userId);

	List<Participation> findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(
		Long roomId, ParticipationStatus status);
}
