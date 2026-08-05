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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

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

	private final List<Long> roomIds = new ArrayList<>();
	private final List<Long> hostUserIds = new ArrayList<>();
	private Long hostUserId;

	@BeforeEach
	void setUp() {
		hostUserId = insertUser();
	}

	@AfterEach
	void tearDown() {
		waitlistCancelFailureGate.deactivate();
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
		@Primary
		RoomWaitlistRepository failingRoomWaitlistRepository(
			@Qualifier("roomWaitlistRepository") RoomWaitlistRepository delegate,
			WaitlistCancelFailureGate waitlistCancelFailureGate) {
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
}
