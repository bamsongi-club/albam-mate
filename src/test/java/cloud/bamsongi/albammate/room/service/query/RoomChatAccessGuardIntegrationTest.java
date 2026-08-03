package cloud.bamsongi.albammate.room.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.contract.ChatAccessGuard;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

@SpringBootTest
@Import(RoomChatAccessGuardIntegrationTest.FixedClockConfiguration.class)
class RoomChatAccessGuardIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

	@Autowired
	private ChatAccessGuard chatAccessGuard;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private ParticipationRepository participationRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final List<Long> roomIds = new ArrayList<>();
	private final List<Long> participationIds = new ArrayList<>();
	private final List<Long> userIds = new ArrayList<>();

	@AfterEach
	void tearDown() {
		participationIds.forEach(participationRepository::deleteById);
		roomIds.forEach(roomRepository::deleteById);
		userIds.forEach(userId -> jdbcTemplate.update("delete from users where id = ?", userId));
	}

	@Test
	void 주최자와_ACTIVE_참가자만_접근하고_참가_취소_사용자는_즉시_거절된다() {
		long hostUserId = insertUser("주최자");
		long participantUserId = insertUser("참가자");
		Room room = saveRoom(hostUserId);
		Participation participation = participationRepository
			.save(Participation.createActive(room, participantUserId, NOW));
		participationIds.add(participation.getId());

		assertEquals("host", chatAccessGuard.executeWithAccess(hostUserId, room.getId(), () -> "host"));
		assertEquals("participant",
			chatAccessGuard.executeWithAccess(participantUserId, room.getId(), () -> "participant"));

		participation.cancel(NOW.plusSeconds(1));
		participationRepository.save(participation);

		assertForbidden(participantUserId, room.getId());
	}

	@Test
	void 최종_상태_ROOM에서_채팅_접근이_거절된다() {
		long hostUserId = insertUser("주최자");
		Room canceledRoom = saveRoom(hostUserId);
		canceledRoom.cancel();
		roomRepository.save(canceledRoom);
		Room finishedRoom = saveRoom(hostUserId);
		finishedRoom.reconcileStateAt(NOW);
		finishedRoom.finishAt(NOW);
		roomRepository.save(finishedRoom);

		assertForbidden(hostUserId, canceledRoom.getId());
		assertForbidden(hostUserId, finishedRoom.getId());
	}

	@Test
	void 시작_24시간이_지난_방은_접근_전에_자동_종료되어_채팅이_거절된다() {
		long hostUserId = insertUser("주최자");
		Room room = saveRoom(hostUserId, NOW.minus(Room.AUTOMATIC_FINISH_AFTER_START));

		assertForbidden(hostUserId, room.getId());
	}

	private void assertForbidden(long userId, long roomId) {
		AtomicBoolean operationExecuted = new AtomicBoolean(false);
		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> chatAccessGuard.executeWithAccess(
				userId,
				roomId,
				() -> {
					operationExecuted.set(true);
					return null;
				}));
		assertFalse(operationExecuted.get(), "접근이 거절되면 채팅 동작이 실행되면 안 됩니다.");
		assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
	}

	private long insertUser(String nickname) {
		String email = "chat-access-" + UUID.randomUUID() + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?)",
			email,
			nickname,
			NOW,
			NOW);
		Long userId = jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
		userIds.add(userId);
		return userId;
	}

	private Room saveRoom(long hostUserId) {
		return saveRoom(hostUserId, NOW.minusSeconds(1));
	}

	private Room saveRoom(long hostUserId, Instant startAt) {
		Room room = Room.create(
			hostUserId,
			RoomType.PERSON_FOCUSED,
			"채팅 접근 방",
			null,
			null,
			ExperienceLevel.ALL_LEVELS,
			false,
			startAt,
			"홍대",
			3);
		Room savedRoom = roomRepository.save(room);
		roomIds.add(savedRoom.getId());
		return savedRoom;
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
