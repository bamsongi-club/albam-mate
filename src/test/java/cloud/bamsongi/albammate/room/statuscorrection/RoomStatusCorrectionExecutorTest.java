package cloud.bamsongi.albammate.room.statuscorrection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.room.contract.RoomTerminalStateReached;
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
@Import(RoomStatusCorrectionExecutorTest.TerminalEventOrderTestConfiguration.class)
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
	private ChatRoomRepository chatRoomRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@Autowired
	private WaitlistExpireFailureGate waitlistExpireFailureGate;
	@Autowired
	private TerminalEventRecorder terminalEventRecorder;

	private final List<Long> roomIds = new ArrayList<>();
	private final List<RoomWaitlistId> waitlistIds = new ArrayList<>();
	private final List<Long> hostUserIds = new ArrayList<>();
	private Long hostUserId;

	@BeforeEach
	void setUp() {
		hostUserId = insertUser();
		terminalEventRecorder.reset();
	}

	@AfterEach
	void tearDown() {
		waitlistExpireFailureGate.deactivate();
		terminalEventRecorder.deactivateFailure();
		roomIds.forEach(roomId -> jdbcTemplate.update("delete from chat_rooms where room_id = ?", roomId));
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
	void 전체_보정은_시작_경계에서_모집중_ROOM을_닫고_기존_닫힌_ROOM의_대기열까지_만료한다() throws ReflectiveOperationException {
		Room recruitingRoom = saveRoom(REQUEST_TIME.minusSeconds(1));
		RoomWaitlist recruitingWaiting = saveWaiting(recruitingRoom.getId());
		Room closedRoom = saveRoom(REQUEST_TIME.minusSeconds(1));
		setStatus(closedRoom, RoomStatus.CLOSED);
		roomRepository.save(closedRoom);
		RoomWaitlist closedWaiting = saveWaiting(closedRoom.getId());

		int changedCount = coordinator.correctDueRooms(REQUEST_TIME);

		assertEquals(2, changedCount);
		assertEquals(RoomStatus.CLOSED, roomRepository.findById(recruitingRoom.getId()).orElseThrow().getStatus());
		assertEquals(
			RoomWaitlistStatus.EXPIRED,
			roomWaitlistRepository.findById(recruitingWaiting.getId()).orElseThrow().getStatus());
		assertEquals(RoomStatus.CLOSED, roomRepository.findById(closedRoom.getId()).orElseThrow().getStatus());
		assertEquals(
			RoomWaitlistStatus.EXPIRED,
			roomWaitlistRepository.findById(closedWaiting.getId()).orElseThrow().getStatus());
	}

	@Test
	void FINISHED_보정은_ROOM과_WAITING을_DB에_반영한_뒤_terminal_event를_한번_발행한다()
		throws ReflectiveOperationException {
		Room room = saveClosedRoom(REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START));
		RoomWaitlist waitlist = saveWaiting(room.getId());
		chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));

		coordinator.correctRoom(room.getId(), REQUEST_TIME);

		assertEquals(RoomStatus.FINISHED, roomRepository.findById(room.getId()).orElseThrow().getStatus());
		assertEquals(
			RoomWaitlistStatus.EXPIRED,
			roomWaitlistRepository.findById(waitlist.getId()).orElseThrow().getStatus());
		assertEquals(1, terminalEventRecorder.count());
		TerminalEventObservation observation = terminalEventRecorder.singleObservation();
		assertEquals(room.getId(), observation.roomId());
		assertEquals(RoomStatus.FINISHED, observation.roomStatus());
		assertEquals(0, observation.waitingCount());
		assertEquals(
			REQUEST_TIME.plusSeconds(30L * 24 * 60 * 60),
			chatRoomRepository.findByRoomId(room.getId()).orElseThrow().getPurgeAfter());
	}

	@Test
	void WAITING_만료_UPDATE_뒤_실패하면_ROOM_대기열_채팅방과_terminal_event를_롤백한다()
		throws ReflectiveOperationException {
		Room room = saveClosedRoom(REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START));
		RoomWaitlist waitlist = saveWaiting(room.getId());
		chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));

		waitlistExpireFailureGate.activate();
		try {
			assertThrows(
				DataIntegrityViolationException.class,
				() -> coordinator.correctRoom(room.getId(), REQUEST_TIME));
		} finally {
			waitlistExpireFailureGate.deactivate();
		}

		assertEquals(RoomStatus.CLOSED, roomRepository.findById(room.getId()).orElseThrow().getStatus());
		assertEquals(
			RoomWaitlistStatus.WAITING,
			roomWaitlistRepository.findById(waitlist.getId()).orElseThrow().getStatus());
		assertNull(chatRoomRepository.findByRoomId(room.getId()).orElseThrow().getPurgeAfter());
		assertEquals(0, terminalEventRecorder.count());
	}

	@Test
	void 시작_경계의_RECRUITING과_CLOSED는_WAITING만_만료하고_terminal_event를_발행하지_않는다()
		throws ReflectiveOperationException {
		Room recruitingRoom = saveRoom(REQUEST_TIME);
		RoomWaitlist recruitingWaiting = saveWaiting(recruitingRoom.getId());
		chatRoomRepository.saveAndFlush(ChatRoom.create(recruitingRoom.getId()));
		Room closedRoom = saveRoom(REQUEST_TIME);
		setStatus(closedRoom, RoomStatus.CLOSED);
		roomRepository.saveAndFlush(closedRoom);
		RoomWaitlist closedWaiting = saveWaiting(closedRoom.getId());
		chatRoomRepository.saveAndFlush(ChatRoom.create(closedRoom.getId()));

		coordinator.correctRoom(recruitingRoom.getId(), REQUEST_TIME);
		coordinator.correctRoom(closedRoom.getId(), REQUEST_TIME);

		assertEquals(RoomStatus.CLOSED, roomRepository.findById(recruitingRoom.getId()).orElseThrow().getStatus());
		assertEquals(
			RoomWaitlistStatus.EXPIRED,
			roomWaitlistRepository.findById(recruitingWaiting.getId()).orElseThrow().getStatus());
		assertNull(chatRoomRepository.findByRoomId(recruitingRoom.getId()).orElseThrow().getPurgeAfter());
		assertEquals(RoomStatus.CLOSED, roomRepository.findById(closedRoom.getId()).orElseThrow().getStatus());
		assertEquals(
			RoomWaitlistStatus.EXPIRED,
			roomWaitlistRepository.findById(closedWaiting.getId()).orElseThrow().getStatus());
		assertNull(chatRoomRepository.findByRoomId(closedRoom.getId()).orElseThrow().getPurgeAfter());
		assertEquals(0, terminalEventRecorder.count());
	}

	@Test
	void FINISHED_보정을_반복해도_ROOM_대기열_채팅방과_terminal_event를_다시_변경하지_않는다()
		throws ReflectiveOperationException {
		Room room = saveClosedRoom(REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START));
		RoomWaitlist waitlist = saveWaiting(room.getId());
		chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));

		coordinator.correctRoom(room.getId(), REQUEST_TIME);

		Room firstFinishedRoom = roomRepository.findById(room.getId()).orElseThrow();
		RoomWaitlist firstExpiredWaiting = roomWaitlistRepository.findById(waitlist.getId()).orElseThrow();
		Instant firstPurgeAfter = chatRoomRepository.findByRoomId(room.getId()).orElseThrow().getPurgeAfter();
		int firstEventCount = terminalEventRecorder.count();

		coordinator.correctRoom(room.getId(), REQUEST_TIME);

		Room repeatedFinishedRoom = roomRepository.findById(room.getId()).orElseThrow();
		RoomWaitlist repeatedExpiredWaiting = roomWaitlistRepository.findById(waitlist.getId()).orElseThrow();
		assertEquals(firstFinishedRoom.getVersion(), repeatedFinishedRoom.getVersion());
		assertEquals(firstExpiredWaiting.getUpdatedAt(), repeatedExpiredWaiting.getUpdatedAt());
		assertEquals(
			firstPurgeAfter,
			chatRoomRepository.findByRoomId(room.getId()).orElseThrow().getPurgeAfter());
		assertEquals(firstEventCount, terminalEventRecorder.count());
	}

	@Test
	void 동기_terminal_listener가_실패하면_ROOM_대기열과_채팅방을_롤백한다()
		throws ReflectiveOperationException {
		Room room = saveClosedRoom(REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START));
		RoomWaitlist waitlist = saveWaiting(room.getId());
		chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));

		terminalEventRecorder.activateFailure();
		try {
			assertThrows(
				DataIntegrityViolationException.class,
				() -> coordinator.correctRoom(room.getId(), REQUEST_TIME));
		} finally {
			terminalEventRecorder.deactivateFailure();
		}

		assertEquals(1, terminalEventRecorder.count());
		assertEquals(RoomStatus.CLOSED, roomRepository.findById(room.getId()).orElseThrow().getStatus());
		assertEquals(
			RoomWaitlistStatus.WAITING,
			roomWaitlistRepository.findById(waitlist.getId()).orElseThrow().getStatus());
		assertNull(chatRoomRepository.findByRoomId(room.getId()).orElseThrow().getPurgeAfter());
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

	private Room saveClosedRoom(Instant startAt) throws ReflectiveOperationException {
		Room room = saveRoom(startAt);
		setStatus(room, RoomStatus.CLOSED);
		return roomRepository.saveAndFlush(room);
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

	@TestConfiguration(proxyBeanMethods = false)
	static class TerminalEventOrderTestConfiguration {

		@Bean
		WaitlistExpireFailureGate waitlistExpireFailureGate() {
			return new WaitlistExpireFailureGate();
		}

		@Bean
		TerminalEventRecorder terminalEventRecorder(JdbcTemplate jdbcTemplate) {
			return new TerminalEventRecorder(jdbcTemplate);
		}

		@Bean
		@Primary
		RoomWaitlistRepository failingRoomWaitlistRepository(
			@Qualifier("roomWaitlistRepository") RoomWaitlistRepository delegate,
			WaitlistExpireFailureGate waitlistExpireFailureGate) {
			return (RoomWaitlistRepository)java.lang.reflect.Proxy.newProxyInstance(
				RoomWaitlistRepository.class.getClassLoader(),
				new Class<?>[] {RoomWaitlistRepository.class},
				(proxy, method, arguments) -> {
					try {
						Object result = method.invoke(delegate, arguments);
						if (method.getName().equals("expireAllWaiting")
							&& waitlistExpireFailureGate.consumeFailureAfterSuccessfulExpire()) {
							throw new DataIntegrityViolationException("테스트 전용 대기 만료 실패");
						}
						return result;
					} catch (InvocationTargetException exception) {
						throw exception.getCause();
					}
				});
		}
	}

	static final class WaitlistExpireFailureGate {

		private final AtomicBoolean active = new AtomicBoolean();

		void activate() {
			assertTrue(active.compareAndSet(false, true));
		}

		boolean consumeFailureAfterSuccessfulExpire() {
			return active.compareAndSet(true, false);
		}

		void deactivate() {
			active.set(false);
		}
	}

	static final class TerminalEventRecorder {

		private final JdbcTemplate jdbcTemplate;
		private final List<TerminalEventObservation> observations = new ArrayList<>();
		private final AtomicBoolean failAfterReceivingEvent = new AtomicBoolean();

		TerminalEventRecorder(JdbcTemplate jdbcTemplate) {
			this.jdbcTemplate = jdbcTemplate;
		}

		@EventListener
		void record(RoomTerminalStateReached event) {
			String roomStatus = jdbcTemplate.queryForObject(
				"select status from rooms where id = ?", String.class, event.roomId());
			Integer waitingCount = jdbcTemplate.queryForObject(
				"select count(*) from room_waitlists where room_id = ? and status = 'WAITING'",
				Integer.class,
				event.roomId());
			observations.add(new TerminalEventObservation(
				event.roomId(), RoomStatus.valueOf(roomStatus), waitingCount));
			if (failAfterReceivingEvent.compareAndSet(true, false)) {
				throw new DataIntegrityViolationException("테스트 전용 terminal listener 실패");
			}
		}

		void reset() {
			observations.clear();
		}

		void activateFailure() {
			assertTrue(failAfterReceivingEvent.compareAndSet(false, true));
		}

		void deactivateFailure() {
			failAfterReceivingEvent.set(false);
		}

		int count() {
			return observations.size();
		}

		TerminalEventObservation singleObservation() {
			return observations.getFirst();
		}
	}

	private record TerminalEventObservation(long roomId, RoomStatus roomStatus, int waitingCount) {
	}
}
