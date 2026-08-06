package cloud.bamsongi.albammate.room.statuscorrection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.entity.RoomWaitlist;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;

@Testcontainers
@SpringBootTest
class RoomStatusCorrectionBoundedPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final Instant REQUEST_TIME = Instant.parse("2026-08-06T00:00:00Z");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("room_status_correction_bounded_test");

	@Autowired
	private RoomStatusCorrectionCoordinator coordinator;
	@Autowired
	private RoomStatusCorrectionProgressStore progressStore;
	@Autowired
	private RoomStatusCorrectionCandidateSelector candidateSelector;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private RoomWaitlistRepository roomWaitlistRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final List<Long> roomIds = new ArrayList<>();
	private final List<Long> userIds = new ArrayList<>();
	private Long hostUserId;
	private Long waitingUserId;

	@BeforeEach
	void setUp() {
		resetProgress();
		hostUserId = insertUser("host");
		waitingUserId = insertUser("waiting");
	}

	@AfterEach
	void tearDown() {
		dropRoomFailureTrigger();
		dropWaitlistFailureTrigger();
		roomIds.forEach(roomId -> {
			jdbcTemplate.update("delete from room_waitlists where room_id = ?", roomId);
			jdbcTemplate.update("delete from rooms where id = ?", roomId);
		});
		userIds.forEach(userId -> jdbcTemplate.update("delete from users where id = ?", userId));
	}

	@Test
	void 제한된_순회는_한_ROOM_실패를_격리하고_커서를_전진한_뒤_다음_실행에서_재처리한다() {
		Room first = saveRoom(REQUEST_TIME.minusSeconds(3));
		Room failed = saveRoom(REQUEST_TIME.minusSeconds(2));
		Room last = saveRoom(REQUEST_TIME.minusSeconds(1));
		installRoomFailureTrigger(failed.getId());

		RoomStatusCorrectionProgressStore.ProgressSnapshot firstClaim = progressStore.claimExecution(REQUEST_TIME);
		int firstChangedCount = coordinator.correctBoundedDueRooms(REQUEST_TIME, firstClaim, 2);

		assertEquals(2, firstChangedCount);
		assertEquals(RoomStatus.CLOSED, currentRoom(first.getId()).getStatus());
		assertEquals(RoomStatus.RECRUITING, currentRoom(failed.getId()).getStatus());
		assertEquals(RoomStatus.CLOSED, currentRoom(last.getId()).getStatus());
		assertCursorWrapped(REQUEST_TIME.plusNanos(1_000));

		dropRoomFailureTrigger();
		Room newlyDue = saveRoom(REQUEST_TIME.plusSeconds(1));
		Instant nextRequestTime = REQUEST_TIME.plusSeconds(2);
		RoomStatusCorrectionProgressStore.ProgressSnapshot nextClaim = progressStore
			.claimExecution(nextRequestTime);
		int secondChangedCount = coordinator.correctBoundedDueRooms(nextRequestTime, nextClaim, 2);

		assertEquals(2, secondChangedCount);
		assertEquals(RoomStatus.CLOSED, currentRoom(failed.getId()).getStatus());
		assertEquals(RoomStatus.CLOSED, currentRoom(newlyDue.getId()).getStatus());
		assertCursorWrapped(nextRequestTime.plusNanos(1_000));
	}

	@Test
	void 세_논리_due_경계와_영속_cursor는_마이크로초_동률에서_다음_tuple만_선택한다() {
		Instant logicalDueAt = REQUEST_TIME.minusNanos(1_000);
		Room recruitingRoom = saveRoom(logicalDueAt);
		Room closedWaitingRoom = saveRoom(logicalDueAt);
		markClosed(closedWaitingRoom.getId());
		saveWaiting(closedWaitingRoom.getId());
		Room closedFinishRoom = saveRoom(logicalDueAt.minus(Room.AUTOMATIC_FINISH_AFTER_START));
		markClosed(closedFinishRoom.getId());

		List<Long> expectedRoomIds = List.of(
			recruitingRoom.getId(), closedWaitingRoom.getId(), closedFinishRoom.getId())
			.stream()
			.sorted()
			.toList();
		RoomStatusCorrectionProgressStore.ProgressSnapshot initialProgress = progressStore
			.claimExecution(REQUEST_TIME);

		List<RoomStatusCorrectionCandidateSelector.DueRoomCandidate> initialCandidates = candidateSelector
			.select(initialProgress, 10);

		assertEquals(999_999_000, logicalDueAt.getNano());
		assertEquals(expectedRoomIds, initialCandidates.stream()
			.map(RoomStatusCorrectionCandidateSelector.DueRoomCandidate::roomId)
			.toList());
		assertEquals(
			List.of(logicalDueAt, logicalDueAt, logicalDueAt),
			initialCandidates.stream()
				.map(RoomStatusCorrectionCandidateSelector.DueRoomCandidate::dueAt)
				.toList());

		Long cursorRoomId = expectedRoomIds.get(1);
		RoomStatusCorrectionProgressStore.ProgressSnapshot advancedProgress = progressStore
			.advanceCursor(initialProgress, logicalDueAt, cursorRoomId)
			.orElseThrow();

		List<RoomStatusCorrectionCandidateSelector.DueRoomCandidate> remainingCandidates = candidateSelector
			.select(advancedProgress, 10);

		assertEquals(cursorRoomId, advancedProgress.cursorRoomId());
		assertEquals(
			List.of(expectedRoomIds.get(2)),
			remainingCandidates.stream()
				.map(RoomStatusCorrectionCandidateSelector.DueRoomCandidate::roomId)
				.toList());
		assertEquals(logicalDueAt, remainingCandidates.getFirst().dueAt());
	}

	@Test
	void 시작_경계의_ROOM_전환과_WAITING_만료는_한_트랜잭션으로_커밋되거나_함께_롤백된다() {
		Room failedRoom = saveRoom(REQUEST_TIME.minusSeconds(1));
		RoomWaitlist failedWaitlist = saveWaiting(failedRoom.getId());
		installWaitlistFailureTrigger(failedRoom.getId());

		assertThrows(RuntimeException.class, () -> coordinator.correctRoom(failedRoom.getId(), REQUEST_TIME));
		assertEquals(RoomStatus.RECRUITING, currentRoom(failedRoom.getId()).getStatus());
		assertEquals(
			RoomWaitlistStatus.WAITING,
			roomWaitlistRepository.findById(failedWaitlist.getId()).orElseThrow().getStatus());

		dropWaitlistFailureTrigger();
		Room successfulRoom = saveRoom(REQUEST_TIME.minusSeconds(1));
		RoomWaitlist successfulWaitlist = saveWaiting(successfulRoom.getId());

		coordinator.correctRoom(successfulRoom.getId(), REQUEST_TIME);

		assertEquals(RoomStatus.CLOSED, currentRoom(successfulRoom.getId()).getStatus());
		assertEquals(
			RoomWaitlistStatus.EXPIRED,
			roomWaitlistRepository.findById(successfulWaitlist.getId()).orElseThrow().getStatus());
	}

	private void resetProgress() {
		jdbcTemplate.update("""
			update room_status_correction_progress
			set turn_cutoff = null,
			    cursor_due_at = null,
			    cursor_room_id = null,
			    progress_version = 0,
			    execution_generation = 0
			where job_name = 'room-status-correction'
			""");
	}

	private void assertCursorWrapped(Instant expectedTurnCutoff) {
		RoomStatusCorrectionProgressStore.ProgressSnapshot progress = progressStore.current();
		assertEquals(expectedTurnCutoff, progress.turnCutoff());
		assertNull(progress.cursorDueAt());
		assertNull(progress.cursorRoomId());
	}

	private Room saveRoom(Instant startAt) {
		Room room = Room.create(
			hostUserId,
			RoomType.PERSON_FOCUSED,
			"제한 순회 테스트 방",
			null,
			null,
			ExperienceLevel.ALL_LEVELS,
			false,
			startAt,
			"홍대 카페",
			3);
		Room saved = roomRepository.saveAndFlush(room);
		roomIds.add(saved.getId());
		return saved;
	}

	private RoomWaitlist saveWaiting(Long roomId) {
		RoomWaitlist waitlist = roomWaitlistRepository.saveAndFlush(
			RoomWaitlist.create(roomId, waitingUserId, roomId, REQUEST_TIME));
		return waitlist;
	}

	private Room currentRoom(Long roomId) {
		return roomRepository.findById(roomId).orElseThrow();
	}

	private void markClosed(Long roomId) {
		jdbcTemplate.update("update rooms set status = 'CLOSED' where id = ?", roomId);
	}

	private Long insertUser(String role) {
		String email = "room-382-" + role + "-" + UUID.randomUUID() + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?)",
			email,
			"ROOM-382 " + role,
			Timestamp.from(REQUEST_TIME),
			Timestamp.from(REQUEST_TIME));
		Long userId = jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
		userIds.add(userId);
		return userId;
	}

	private void installRoomFailureTrigger(Long failedRoomId) {
		jdbcTemplate.execute("""
			create or replace function room_382_fail_update() returns trigger language plpgsql as $$
			begin
			    if new.id = %d then
			        raise exception 'ROOM-382 injected room update failure';
			    end if;
			    return new;
			end;
			$$
			""".formatted(failedRoomId));
		jdbcTemplate.execute("""
			create trigger room_382_fail_update_trigger
			before update of status on rooms
			for each row execute function room_382_fail_update()
			""");
	}

	private void dropRoomFailureTrigger() {
		jdbcTemplate.execute("drop trigger if exists room_382_fail_update_trigger on rooms");
		jdbcTemplate.execute("drop function if exists room_382_fail_update()");
	}

	private void installWaitlistFailureTrigger(Long failedRoomId) {
		jdbcTemplate.execute("""
			create or replace function room_382_fail_waitlist_expiry() returns trigger language plpgsql as $$
			begin
			    if new.room_id = %d and new.status = 'EXPIRED' then
			        raise exception 'ROOM-382 injected waitlist expiry failure';
			    end if;
			    return new;
			end;
			$$
			""".formatted(failedRoomId));
		jdbcTemplate.execute("""
			create trigger room_382_fail_waitlist_expiry_trigger
			before update of status on room_waitlists
			for each row execute function room_382_fail_waitlist_expiry()
			""");
	}

	private void dropWaitlistFailureTrigger() {
		jdbcTemplate.execute("drop trigger if exists room_382_fail_waitlist_expiry_trigger on room_waitlists");
		jdbcTemplate.execute("drop function if exists room_382_fail_waitlist_expiry()");
	}
}
