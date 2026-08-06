package cloud.bamsongi.albammate.room.statuscorrection;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** ROOM 상태 보정 순회의 영속 cursor와 실행 세대를 보관한다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "room_status_correction_progress")
class RoomStatusCorrectionProgress {

	static final String JOB_NAME = "room-status-correction";

	@Id
	@Column(name = "job_name", nullable = false, length = 64)
	private String jobName;

	@Column(name = "turn_cutoff")
	private Instant turnCutoff;

	@Column(name = "cursor_due_at")
	private Instant cursorDueAt;

	@Column(name = "cursor_room_id")
	private Long cursorRoomId;

	@Column(name = "progress_version", nullable = false)
	private long progressVersion;

	@Column(name = "execution_generation", nullable = false)
	private long executionGeneration;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

}
