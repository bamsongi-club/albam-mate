package cloud.bamsongi.albammate.room.service.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.room.contract.RoomTerminalStateReached;
import cloud.bamsongi.albammate.room.dto.RoomStatusResponse;
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
@Import(RoomStatusChangeExecutorIntegrationTest.WaitlistCancelFailureConfiguration.class)
class RoomStatusChangeExecutorIntegrationTest {

	private static final Instant REQUEST_TIME = Instant.parse("2026-07-28T00:00:00Z");

	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private RoomStatusChangeExecutor executor;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private RoomWaitlistRepository roomWaitlistRepository;
	@Autowired
	private WaitlistCancelFailureGate waitlistCancelFailureGate;
	@Autowired
	private WaitlistExpireFailureGate waitlistExpireFailureGate;
	@Autowired
	private ChatRoomRepository chatRoomRepository;
	@Autowired
	private TerminalEventRecorder terminalEventRecorder;

	private final List<Long> roomIds = new ArrayList<>();
	private final List<Long> hostUserIds = new ArrayList<>();
	private Long hostUserId;

	@BeforeEach
	void setUp() {
		hostUserId = insertUser();
		terminalEventRecorder.reset();
	}

	@AfterEach
	void tearDown() {
		waitlistCancelFailureGate.deactivate();
		waitlistExpireFailureGate.deactivate();
		roomIds.forEach(roomId -> jdbcTemplate.update("delete from chat_rooms where room_id = ?", roomId));
		roomIds.forEach(roomId -> jdbcTemplate.update("delete from room_waitlists where room_id = ?", roomId));
		roomIds.forEach(roomId -> roomRepository.deleteById(roomId));
		hostUserIds.forEach(
			userId -> jdbcTemplate.update("delete from users where id = ?", userId));
	}

	@Test
	void ROOM_취소는_현재_WAITING만_ROOM_CANCELED로_종료하고_함께_커밋한다() {
		Room room = saveRoom(REQUEST_TIME.plusSeconds(3600));
		long waitingUserId = insertUser();
		long alreadyCanceledUserId = insertUser();
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(room.getId(), waitingUserId, 10L, REQUEST_TIME));
		roomWaitlistRepository.saveAndFlush(
			RoomWaitlist.create(room.getId(), alreadyCanceledUserId, 20L, REQUEST_TIME));
		jdbcTemplate.update(
			"update room_waitlists set status = 'CANCELED', updated_at = ? where room_id = ? and user_id = ?",
			REQUEST_TIME.plusSeconds(30),
			room.getId(),
			alreadyCanceledUserId);

		RoomStatusResponse response = executor.cancelRoom(hostUserId, room.getId(), REQUEST_TIME);

		assertEquals(RoomStatus.CANCELED, response.roomStatus());
		assertEquals(RoomStatus.CANCELED, roomRepository.findById(room.getId()).orElseThrow().getStatus());
		assertEquals(RoomWaitlistStatus.ROOM_CANCELED, roomWaitlistRepository
			.findById(new RoomWaitlistId(room.getId(), waitingUserId))
			.orElseThrow()
			.getStatus());
		assertEquals(RoomWaitlistStatus.CANCELED, roomWaitlistRepository
			.findById(new RoomWaitlistId(room.getId(), alreadyCanceledUserId))
			.orElseThrow()
			.getStatus());
	}

	@Test
	void 대기자가_없는_ROOM_취소도_정상적으로_완료한다() {
		Room room = saveRoom(REQUEST_TIME.plusSeconds(3600));

		RoomStatusResponse response = executor.cancelRoom(hostUserId, room.getId(), REQUEST_TIME);

		assertEquals(RoomStatus.CANCELED, response.roomStatus());
		assertEquals(RoomStatus.CANCELED, roomRepository.findById(room.getId()).orElseThrow().getStatus());
	}

	@Test
	void 대기_종료_실패는_이미_flush된_ROOM_취소와_WAITING_종료를_같은_REQUIRES_NEW_트랜잭션에서_롤백한다() {
		Room room = saveRoom(REQUEST_TIME.plusSeconds(3600));
		long waitingUserId = insertUser();
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(room.getId(), waitingUserId, 10L, REQUEST_TIME));

		waitlistCancelFailureGate.activate();
		try {
			assertThrows(
				DataIntegrityViolationException.class,
				() -> executor.cancelRoom(hostUserId, room.getId(), REQUEST_TIME));
		} finally {
			waitlistCancelFailureGate.deactivate();
		}

		assertEquals(RoomStatus.RECRUITING, roomRepository.findById(room.getId()).orElseThrow().getStatus());
		assertEquals(RoomWaitlistStatus.WAITING, roomWaitlistRepository
			.findById(new RoomWaitlistId(room.getId(), waitingUserId))
			.orElseThrow()
			.getStatus());
	}

	@Test
	void 자동_정합화로_FINISHED가_된_종료는_상태와_버전을_커밋한다() {
		Room room = saveRoom(REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START));
		Long versionBefore = roomRepository.findById(room.getId()).orElseThrow().getVersion();

		RoomStatusResponse response = executor.finishRoom(hostUserId, room.getId(), REQUEST_TIME);

		Room finished = roomRepository.findById(room.getId()).orElseThrow();
		assertEquals(RoomStatus.FINISHED, response.roomStatus());
		assertEquals(RoomStatus.FINISHED, finished.getStatus());
		assertTrue(finished.getVersion() > versionBefore);
	}

	@Test
	void 수동_종료는_WAITING을_EXPIRED로_하고_CANCELED를_보존하며_채팅방_보관기한을_설정한다() throws ReflectiveOperationException {
		Room room = saveRoom(REQUEST_TIME);
		setStatus(room, RoomStatus.CLOSED);
		roomRepository.saveAndFlush(room);
		long firstWaitingUserId = insertUser();
		long canceledUserId = insertUser();
		long secondWaitingUserId = insertUser();
		saveWaiting(room.getId(), firstWaitingUserId, 10L);
		saveWaiting(room.getId(), canceledUserId, 20L);
		saveWaiting(room.getId(), secondWaitingUserId, 30L);
		jdbcTemplate.update(
			"update room_waitlists set status = 'CANCELED', updated_at = ? where room_id = ? and user_id = ?",
			REQUEST_TIME.plusSeconds(30),
			room.getId(),
			canceledUserId);
		chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));

		RoomStatusResponse response = executor.finishRoom(hostUserId, room.getId(), REQUEST_TIME);

		assertEquals(RoomStatus.FINISHED, response.roomStatus());
		assertEquals(RoomStatus.FINISHED, roomRepository.findById(room.getId()).orElseThrow().getStatus());
		assertEquals(RoomWaitlistStatus.EXPIRED, waitlistStatus(room.getId(), firstWaitingUserId));
		assertEquals(RoomWaitlistStatus.CANCELED, waitlistStatus(room.getId(), canceledUserId));
		assertEquals(RoomWaitlistStatus.EXPIRED, waitlistStatus(room.getId(), secondWaitingUserId));
		assertSingleTerminalEvent(room.getId());
		assertEquals(
			REQUEST_TIME.plusSeconds(30L * 24 * 60 * 60),
			chatRoomRepository.findByRoomId(room.getId()).orElseThrow().getPurgeAfter());
	}

	@Test
	void 자동_종료_보정의_RECRUITING_방은_WAITING을_EXPIRED로_하고_채팅방_보관기한을_설정한다() {
		Room room = saveRoom(REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START));
		long waitingUserId = insertUser();
		saveWaiting(room.getId(), waitingUserId, 10L);
		chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));

		RoomStatusResponse response = executor.finishRoom(hostUserId, room.getId(), REQUEST_TIME);

		assertEquals(RoomStatus.FINISHED, response.roomStatus());
		assertEquals(RoomWaitlistStatus.EXPIRED, waitlistStatus(room.getId(), waitingUserId));
		assertSingleTerminalEvent(room.getId());
		assertEquals(
			REQUEST_TIME.plusSeconds(30L * 24 * 60 * 60),
			chatRoomRepository.findByRoomId(room.getId()).orElseThrow().getPurgeAfter());
	}

	@Test
	void 자동_종료_보정의_CLOSED_방은_WAITING을_EXPIRED로_하고_채팅방_보관기한을_설정한다() throws ReflectiveOperationException {
		Room room = saveRoom(REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START));
		setStatus(room, RoomStatus.CLOSED);
		roomRepository.saveAndFlush(room);
		long waitingUserId = insertUser();
		saveWaiting(room.getId(), waitingUserId, 10L);
		chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));

		RoomStatusResponse response = executor.finishRoom(hostUserId, room.getId(), REQUEST_TIME);

		assertEquals(RoomStatus.FINISHED, response.roomStatus());
		assertEquals(RoomWaitlistStatus.EXPIRED, waitlistStatus(room.getId(), waitingUserId));
		assertSingleTerminalEvent(room.getId());
		assertEquals(
			REQUEST_TIME.plusSeconds(30L * 24 * 60 * 60),
			chatRoomRepository.findByRoomId(room.getId()).orElseThrow().getPurgeAfter());
	}

	@Test
	void 실제_WAITING_만료_UPDATE_뒤_실패하면_ROOM_대기_이벤트와_채팅방_보관기한을_모두_롤백한다() throws ReflectiveOperationException {
		Room room = saveRoom(REQUEST_TIME);
		setStatus(room, RoomStatus.CLOSED);
		roomRepository.saveAndFlush(room);
		long firstWaitingUserId = insertUser();
		long secondWaitingUserId = insertUser();
		saveWaiting(room.getId(), firstWaitingUserId, 10L);
		saveWaiting(room.getId(), secondWaitingUserId, 20L);
		chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));

		waitlistExpireFailureGate.activate();
		try {
			assertThrows(
				DataIntegrityViolationException.class,
				() -> executor.finishRoom(hostUserId, room.getId(), REQUEST_TIME));
		} finally {
			waitlistExpireFailureGate.deactivate();
		}

		assertEquals(RoomStatus.CLOSED, roomRepository.findById(room.getId()).orElseThrow().getStatus());
		assertEquals(RoomWaitlistStatus.WAITING, waitlistStatus(room.getId(), firstWaitingUserId));
		assertEquals(RoomWaitlistStatus.WAITING, waitlistStatus(room.getId(), secondWaitingUserId));
		assertEquals(0, terminalEventRecorder.count());
		assertEquals(null, chatRoomRepository.findByRoomId(room.getId()).orElseThrow().getPurgeAfter());
	}

	@Test
	void 이미_FINISHED인_방의_종료는_상태_버전_갱신시각을_변경하지_않는다() throws ReflectiveOperationException {
		Room room = saveRoom(REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START));
		setStatus(room, RoomStatus.FINISHED);
		roomRepository.save(room);
		Room before = roomRepository.findById(room.getId()).orElseThrow();

		RoomStatusResponse response = executor.finishRoom(hostUserId, room.getId(), REQUEST_TIME);

		Room after = roomRepository.findById(room.getId()).orElseThrow();
		assertEquals(RoomStatus.FINISHED, response.roomStatus());
		assertEquals(RoomStatus.FINISHED, after.getStatus());
		assertEquals(before.getVersion(), after.getVersion());
		assertEquals(before.getUpdatedAt(), after.getUpdatedAt());
	}

	private Room saveRoom(Instant startAt) {
		Room room = Room.create(
			hostUserId,
			RoomType.PERSON_FOCUSED,
			"종료 테스트 방",
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

	private Long insertUser() {
		String email = "room-status-change-" + UUID.randomUUID() + "@example.com";
		jdbcTemplate.update(
			"insert into users "
				+ "(email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'fixture-password-hash', '종료 테스트', ?, ?)",
			email,
			REQUEST_TIME,
			REQUEST_TIME);
		Long userId = jdbcTemplate.queryForObject(
			"select id from users where email = ?", Long.class, email);
		hostUserIds.add(userId);
		return userId;
	}

	private void saveWaiting(long roomId, long userId, long queueOrder) {
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(roomId, userId, queueOrder, REQUEST_TIME));
	}

	private RoomWaitlistStatus waitlistStatus(long roomId, long userId) {
		String status = jdbcTemplate.queryForObject(
			"select status from room_waitlists where room_id = ? and user_id = ?",
			String.class,
			roomId,
			userId);
		return RoomWaitlistStatus.valueOf(status);
	}

	private void assertSingleTerminalEvent(long roomId) {
		assertEquals(1, terminalEventRecorder.count());
		RoomTerminalStateReached event = terminalEventRecorder.singleEvent();
		assertEquals(roomId, event.roomId());
		assertEquals(REQUEST_TIME, event.reachedAt());
	}

	private void setStatus(Room room, RoomStatus status) throws ReflectiveOperationException {
		Field field = Room.class.getDeclaredField("status");
		field.setAccessible(true);
		field.set(room, status);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class WaitlistCancelFailureConfiguration {

		@Bean
		WaitlistCancelFailureGate waitlistCancelFailureGate() {
			return new WaitlistCancelFailureGate();
		}

		@Bean
		WaitlistExpireFailureGate waitlistExpireFailureGate() {
			return new WaitlistExpireFailureGate();
		}

		@Bean
		TerminalEventRecorder terminalEventRecorder() {
			return new TerminalEventRecorder();
		}

		@Bean
		@Primary
		RoomWaitlistRepository failingRoomWaitlistRepository(
			@Qualifier("roomWaitlistRepository") RoomWaitlistRepository delegate,
			WaitlistCancelFailureGate waitlistCancelFailureGate,
			WaitlistExpireFailureGate waitlistExpireFailureGate) {
			return (RoomWaitlistRepository)java.lang.reflect.Proxy.newProxyInstance(
				RoomWaitlistRepository.class.getClassLoader(),
				new Class<?>[] {RoomWaitlistRepository.class},
				(proxy, method, arguments) -> {
					try {
						Object result = method.invoke(delegate, arguments);
						if (method.getName().equals("cancelAllWaiting")
							&& waitlistCancelFailureGate.consumeFailureAfterSuccessfulCancel()) {
							throw new DataIntegrityViolationException("테스트 전용 대기 종료 실패");
						}
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

	static final class WaitlistCancelFailureGate {

		private final AtomicBoolean active = new AtomicBoolean();

		void activate() {
			assertTrue(active.compareAndSet(false, true));
		}

		boolean consumeFailureAfterSuccessfulCancel() {
			return active.compareAndSet(true, false);
		}

		void deactivate() {
			active.set(false);
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

		private final List<RoomTerminalStateReached> events = new ArrayList<>();

		@EventListener
		void record(RoomTerminalStateReached event) {
			events.add(event);
		}

		void reset() {
			events.clear();
		}

		int count() {
			return events.size();
		}

		RoomTerminalStateReached singleEvent() {
			return events.getFirst();
		}
	}
}
