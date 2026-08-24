package cloud.bamsongi.albammate.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.room.dto.CreateRoomRequest;
import cloud.bamsongi.albammate.room.dto.ParticipantRoomResponse;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.command.RoomCreateService;
import cloud.bamsongi.albammate.room.statuscorrection.RoomStatusCorrectionCoordinator;

@SpringBootTest
@Import(ChatRoomLifecycleIntegrationTest.FixedClockConfiguration.class)
class ChatRoomLifecycleIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

	@Autowired
	private RoomCreateService roomCreateService;
	@Autowired
	private ChatRoomRepository chatRoomRepository;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private RoomStatusCorrectionCoordinator statusCorrectionCoordinator;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private TransactionTemplate transactionTemplate;

	private final List<Long> userIds = new ArrayList<>();
	private final List<Long> roomIds = new ArrayList<>();

	@AfterEach
	void tearDown() {
		roomIds.forEach(roomId -> {
			jdbcTemplate.update("delete from chat_rooms where room_id = ?", roomId);
			jdbcTemplate.update("delete from rooms where id = ?", roomId);
		});
		userIds.forEach(userId -> jdbcTemplate.update("delete from users where id = ?", userId));
	}

	@Test
	void 방_생성_트랜잭션이_커밋되면_채팅방이_함께_생성된다() {
		long hostUserId = insertUser();

		ParticipantRoomResponse response = roomCreateService.createRoom(hostUserId, request());
		roomIds.add(response.id());

		assertEquals(1, chatRoomRepository.findByRoomId(response.id()).stream().count());
	}

	@Test
	void 방_생성_트랜잭션이_롤백되면_채팅방이_남지_않는다() {
		long hostUserId = insertUser();

		Long roomId = transactionTemplate.execute(status -> {
			ParticipantRoomResponse response = roomCreateService.createRoom(hostUserId, request());
			status.setRollbackOnly();
			return response.id();
		});

		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from rooms where id = ?", Integer.class, roomId));
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from chat_rooms where room_id = ?", Integer.class, roomId));
	}

	@Test
	void 자동_종료_상태_보정은_채팅방_보관_기한을_30일_뒤로_설정한다() {
		long hostUserId = insertUser();
		Room room = Room.create(
			hostUserId,
			RoomType.PERSON_FOCUSED,
			"자동 종료 채팅방",
			null,
			null,
			ExperienceLevel.ALL_LEVELS,
			false,
			NOW.minus(Duration.ofDays(1)),
			"홍대",
			3);
		room = roomRepository.save(room);
		roomIds.add(room.getId());
		chatRoomRepository.save(ChatRoom.create(room.getId()));

		statusCorrectionCoordinator.correctRoom(room.getId(), NOW);
		statusCorrectionCoordinator.correctRoom(room.getId(), NOW);

		assertEquals(RoomStatus.FINISHED, roomRepository.findById(room.getId()).orElseThrow().getStatus());
		assertEquals(
			NOW.plus(Duration.ofDays(30)),
			chatRoomRepository.findByRoomId(room.getId()).orElseThrow().getPurgeAfter());
	}

	private long insertUser() {
		String email = "chat-lifecycle-" + UUID.randomUUID() + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', '주최자', ?, ?)",
			email,
			NOW,
			NOW);
		Long userId = jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
		userIds.add(userId);
		return userId;
	}

	private CreateRoomRequest request() {
		return new CreateRoomRequest(
			RoomType.PERSON_FOCUSED,
			"채팅 수명주기 방",
			null,
			null,
			ExperienceLevel.ALL_LEVELS,
			false,
			NOW.plusSeconds(3600),
			"홍대",
			3);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfiguration {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}
}
