package cloud.bamsongi.albammate.room.statuscorrection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.entity.RoomWaitlist;
import cloud.bamsongi.albammate.room.entity.RoomWaitlistId;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;

@SpringBootTest
class RoomStatusCorrectionExecutorTest {

	private static final Instant REQUEST_TIME = Instant.parse("2026-07-27T00:00:00Z");

	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private RoomStatusCorrectionCoordinator coordinator;
	@Autowired
	private RoomStatusCorrectionCandidateSelector candidateSelector;
	@Autowired
	private RoomWaitlistRepository roomWaitlistRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private final List<Long> roomIds = new ArrayList<>();
	private final List<RoomWaitlistId> waitlistIds = new ArrayList<>();
	private final List<Long> hostUserIds = new ArrayList<>();
	private Long hostUserId;

	@BeforeEach
	void setUp() {
		hostUserId = insertUser();
	}

	@AfterEach
	void tearDown() {
		waitlistIds.forEach(waitlistId -> roomWaitlistRepository.deleteById(waitlistId));
		roomIds.forEach(roomId -> roomRepository.deleteById(roomId));
		hostUserIds.forEach(
			userId -> jdbcTemplate.update("delete from users where id = ?", userId));
	}

	@Test
	void 단건_보정은_버전을_증가시키고_두번째_호출에서는_상태와_버전을_그대로_둔다() {
		Room room = saveRoom(REQUEST_TIME.minusSeconds(1));
		Long versionBefore = roomRepository.findById(room.getId()).orElseThrow().getVersion();

		coordinator.correctRoom(room.getId(), REQUEST_TIME);

		Room reconciled = roomRepository.findById(room.getId()).orElseThrow();
		assertEquals(RoomStatus.CLOSED, reconciled.getStatus());
		assertTrue(reconciled.getVersion() > versionBefore);
		Long versionAfter = reconciled.getVersion();

		coordinator.correctRoom(room.getId(), REQUEST_TIME);

		Room unchanged = roomRepository.findById(room.getId()).orElseThrow();
		assertEquals(RoomStatus.CLOSED, unchanged.getStatus());
		assertEquals(versionAfter, unchanged.getVersion());
	}

	@Test
	void 전체_보정은_due_방만_선택하고_오래_지난_모집중은_종료까지_전이한다() throws ReflectiveOperationException {
		Room oldRecruiting = saveRoom(REQUEST_TIME.minusSeconds(25 * 60 * 60));
		Room dueClosed = saveRoom(REQUEST_TIME.minusSeconds(25 * 60 * 60));
		setStatus(dueClosed, RoomStatus.CLOSED);
		dueClosed = roomRepository.save(dueClosed);
		Room recruitingAtStart = saveRoom(REQUEST_TIME);
		// H2와 PostgreSQL의 TIMESTAMP WITH TIME ZONE 기본 정밀도에 맞춰 경계 직후를 1µs로 표현한다.
		Room recruitingAfterStart = saveRoom(REQUEST_TIME.plusNanos(1_000));
		Room closedAtFinish = saveRoom(REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START));
		setStatus(closedAtFinish, RoomStatus.CLOSED);
		closedAtFinish = roomRepository.save(closedAtFinish);
		Room closedAfterFinish = saveRoom(REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START).plusNanos(1_000));
		setStatus(closedAfterFinish, RoomStatus.CLOSED);
		closedAfterFinish = roomRepository.save(closedAfterFinish);
		Room future = saveRoom(REQUEST_TIME.plusSeconds(60 * 60));
		Room canceled = saveRoom(REQUEST_TIME.minusSeconds(25 * 60 * 60));
		setStatus(canceled, RoomStatus.CANCELED);
		canceled = roomRepository.save(canceled);
		Room finished = saveRoom(REQUEST_TIME.minusSeconds(25 * 60 * 60));
		setStatus(finished, RoomStatus.FINISHED);
		finished = roomRepository.save(finished);

		Long futureVersion = future.getVersion();
		Long canceledVersion = canceled.getVersion();
		Long finishedVersion = finished.getVersion();
		Long recruitingAfterStartVersion = recruitingAfterStart.getVersion();
		Long closedAfterFinishVersion = closedAfterFinish.getVersion();

		int changedCount = coordinator.correctDueRooms(REQUEST_TIME);

		assertEquals(4, changedCount);

		assertEquals(
			RoomStatus.FINISHED,
			roomRepository.findById(oldRecruiting.getId()).orElseThrow().getStatus());
		assertEquals(
			RoomStatus.FINISHED,
			roomRepository.findById(dueClosed.getId()).orElseThrow().getStatus());
		assertEquals(
			RoomStatus.CLOSED,
			roomRepository.findById(recruitingAtStart.getId()).orElseThrow().getStatus());
		assertEquals(
			RoomStatus.RECRUITING,
			roomRepository.findById(recruitingAfterStart.getId()).orElseThrow().getStatus());
		assertEquals(
			RoomStatus.FINISHED,
			roomRepository.findById(closedAtFinish.getId()).orElseThrow().getStatus());
		assertEquals(
			RoomStatus.CLOSED,
			roomRepository.findById(closedAfterFinish.getId()).orElseThrow().getStatus());
		assertEquals(
			RoomStatus.RECRUITING,
			roomRepository.findById(future.getId()).orElseThrow().getStatus());
		assertEquals(
			RoomStatus.CANCELED,
			roomRepository.findById(canceled.getId()).orElseThrow().getStatus());
		assertEquals(
			RoomStatus.FINISHED,
			roomRepository.findById(finished.getId()).orElseThrow().getStatus());
		assertEquals(
			futureVersion, roomRepository.findById(future.getId()).orElseThrow().getVersion());
		assertEquals(
			canceledVersion,
			roomRepository.findById(canceled.getId()).orElseThrow().getVersion());
		assertEquals(
			finishedVersion,
			roomRepository.findById(finished.getId()).orElseThrow().getVersion());
		assertEquals(
			recruitingAfterStartVersion,
			roomRepository.findById(recruitingAfterStart.getId()).orElseThrow().getVersion());
		assertEquals(
			closedAfterFinishVersion,
			roomRepository.findById(closedAfterFinish.getId()).orElseThrow().getVersion());
	}

	@Test
	void 단건_미존재는_오류없이_종료한다() {
		coordinator.correctRoom(Long.MAX_VALUE, REQUEST_TIME);
	}

	@Test
	void 시작_경계_보정은_ROOM과_대기열을_같은_단건_경로에서_처리한다() {
		Room room = saveRoom(REQUEST_TIME.minusSeconds(1));
		RoomWaitlist waitlist = saveWaiting(room.getId());

		coordinator.correctRoom(room.getId(), REQUEST_TIME);

		assertEquals(RoomStatus.CLOSED, roomRepository.findById(room.getId()).orElseThrow().getStatus());
		assertEquals(
			RoomWaitlistStatus.EXPIRED,
			roomWaitlistRepository.findById(waitlist.getId()).orElseThrow().getStatus());
	}

	@Test
	void 제한_후보_조회는_세_경계의_논리_due_순서와_cursor_조건을_적용한다() throws ReflectiveOperationException {
		Room recruitingAtStart = saveRoom(REQUEST_TIME.minusSeconds(1));
		Room closedWithWaiting = saveRoom(REQUEST_TIME.minusSeconds(1));
		setStatus(closedWithWaiting, RoomStatus.CLOSED);
		roomRepository.save(closedWithWaiting);
		saveWaiting(closedWithWaiting.getId());
		Room closedAtFinish = saveRoom(
			REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START).minusSeconds(1));
		setStatus(closedAtFinish, RoomStatus.CLOSED);
		roomRepository.save(closedAtFinish);
		Room future = saveRoom(REQUEST_TIME.plusSeconds(1));

		List<RoomStatusCorrectionCandidateSelector.DueRoomCandidate> selected = candidateSelector.select(
			new RoomStatusCorrectionProgressStore.ProgressSnapshot(REQUEST_TIME, null, null, 0L, 0L), 10);

		assertEquals(
			List.of(recruitingAtStart.getId(), closedWithWaiting.getId(), closedAtFinish.getId()),
			selected.stream().map(RoomStatusCorrectionCandidateSelector.DueRoomCandidate::roomId).toList());
		assertEquals(
			List.of(REQUEST_TIME.minusSeconds(1), REQUEST_TIME.minusSeconds(1), REQUEST_TIME.minusSeconds(1)),
			selected.stream().map(RoomStatusCorrectionCandidateSelector.DueRoomCandidate::dueAt).toList());
		assertTrue(selected.stream().noneMatch(candidate -> candidate.roomId().equals(future.getId())));
	}

	@Test
	void 외부_트랜잭션이_롤백되어도_REQUIRES_NEW_보정은_커밋된다() {
		Room room = saveRoom(REQUEST_TIME.minusSeconds(1));
		Long versionBefore = roomRepository.findById(room.getId()).orElseThrow().getVersion();

		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
		transactionTemplate.executeWithoutResult(
			status -> {
				coordinator.correctRoom(room.getId(), REQUEST_TIME);
				status.setRollbackOnly();
			});

		Room reconciled = roomRepository.findById(room.getId()).orElseThrow();
		assertEquals(RoomStatus.CLOSED, reconciled.getStatus());
		assertTrue(reconciled.getVersion() > versionBefore);
	}

	private Room saveRoom(Instant startAt) {
		Room room = Room.create(
			hostUserId,
			RoomType.PERSON_FOCUSED,
			"상태 보정 방",
			null,
			null,
			ExperienceLevel.ALL_LEVELS,
			false,
			startAt,
			"홍대 카페",
			3);
		Room saved = roomRepository.save(room);
		roomIds.add(saved.getId());
		return saved;
	}

	private RoomWaitlist saveWaiting(Long roomId) {
		RoomWaitlist waitlist = roomWaitlistRepository.save(
			RoomWaitlist.create(roomId, hostUserId, roomId, REQUEST_TIME.minusSeconds(2)));
		waitlistIds.add(waitlist.getId());
		return waitlist;
	}

	private Long insertUser() {
		String email = "room-reconciliation-" + UUID.randomUUID() + "@example.com";
		jdbcTemplate.update(
			"insert into users "
				+ "(email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'fixture-password-hash', '보정 테스트', ?, ?)",
			email,
			REQUEST_TIME,
			REQUEST_TIME);
		Long userId = jdbcTemplate.queryForObject(
			"select id from users where email = ?", Long.class, email);
		hostUserIds.add(userId);
		return userId;
	}

	private void setStatus(Room room, RoomStatus status) throws ReflectiveOperationException {
		Field field = Room.class.getDeclaredField("status");
		field.setAccessible(true);
		field.set(room, status);
	}
}
