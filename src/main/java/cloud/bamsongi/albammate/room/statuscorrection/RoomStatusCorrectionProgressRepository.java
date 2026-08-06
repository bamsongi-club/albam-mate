package cloud.bamsongi.albammate.room.statuscorrection;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface RoomStatusCorrectionProgressRepository extends JpaRepository<RoomStatusCorrectionProgress, String> {

	default RoomStatusCorrectionProgress findCurrent() {
		return findById(RoomStatusCorrectionProgress.JOB_NAME)
			.orElseThrow(() -> new IllegalStateException("ROOM 상태 보정 progress 행이 없습니다."));
	}

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select progress from RoomStatusCorrectionProgress progress where progress.jobName = :jobName")
	Optional<RoomStatusCorrectionProgress> findByJobNameForUpdate(@Param("jobName")
	String jobName);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
		update room_status_correction_progress
		set turn_cutoff = case
				when turn_cutoff is null or cursor_due_at is null then
					case when turn_cutoff is null or turn_cutoff < :requestTime
						then :requestTime else turn_cutoff end
				else turn_cutoff end,
			execution_generation = execution_generation + 1,
			progress_version = progress_version + 1,
			updated_at = CURRENT_TIMESTAMP
		where job_name = :jobName
		""", nativeQuery = true)
	int claimExecution(
		@Param("jobName")
		String jobName,
		@Param("requestTime")
		Instant requestTime);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
		update room_status_correction_progress
		set cursor_due_at = :cursorDueAt,
		    cursor_room_id = :cursorRoomId,
		    progress_version = progress_version + 1,
		    updated_at = CURRENT_TIMESTAMP
		where job_name = :jobName
		  and progress_version = :expectedVersion
		  and execution_generation = :expectedGeneration
		  and turn_cutoff >= :cursorDueAt
		  and (cursor_due_at is null
		       or cursor_due_at < :cursorDueAt
		       or (cursor_due_at = :cursorDueAt and cursor_room_id < :cursorRoomId))
		""", nativeQuery = true)
	int advanceCursor(
		@Param("jobName")
		String jobName,
		@Param("expectedVersion")
		long expectedVersion,
		@Param("expectedGeneration")
		long expectedGeneration,
		@Param("cursorDueAt")
		Instant cursorDueAt,
		@Param("cursorRoomId")
		long cursorRoomId);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
		update room_status_correction_progress
		set turn_cutoff = :nextTurnCutoff,
		    cursor_due_at = null,
		    cursor_room_id = null,
		    progress_version = progress_version + 1,
		    updated_at = CURRENT_TIMESTAMP
		where job_name = :jobName
		  and progress_version = :expectedVersion
		  and execution_generation = :expectedGeneration
		  and turn_cutoff < :nextTurnCutoff
		""", nativeQuery = true)
	int wrap(
		@Param("jobName")
		String jobName,
		@Param("expectedVersion")
		long expectedVersion,
		@Param("expectedGeneration")
		long expectedGeneration,
		@Param("nextTurnCutoff")
		Instant nextTurnCutoff);
}
