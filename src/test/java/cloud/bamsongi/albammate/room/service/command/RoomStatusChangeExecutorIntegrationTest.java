package cloud.bamsongi.albammate.room.service.command;

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

import cloud.bamsongi.albammate.room.dto.RoomStatusResponse;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

@SpringBootTest
class RoomStatusChangeExecutorIntegrationTest {

	private static final Instant REQUEST_TIME = Instant.parse("2026-07-28T00:00:00Z");

	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private RoomStatusChangeExecutor executor;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final List<Long> roomIds = new ArrayList<>();
	private final List<Long> hostUserIds = new ArrayList<>();
	private Long hostUserId;

	@BeforeEach
	void setUp() {
		hostUserId = insertUser();
	}

	@AfterEach
	void tearDown() {
		roomIds.forEach(roomId -> roomRepository.deleteById(roomId));
		hostUserIds.forEach(
			userId -> jdbcTemplate.update("delete from users where id = ?", userId));
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
}
