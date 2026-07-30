package cloud.bamsongi.albammate.room.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.RoomOptimisticLockRetrier;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;

@SpringBootTest
@Import(RoomParticipationServiceTest.FixedClockConfiguration.class)
class RoomParticipationServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

	@Autowired
	private RoomParticipationService roomParticipationService;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private ParticipationRepository participationRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private EntityManager entityManager;

	@Test
	void 세_번_낙관_락_충돌은_재시도_로그와_최종_경고를_한번씩_남긴다() {
		RoomParticipationExecutor executor = org.mockito.Mockito.mock(RoomParticipationExecutor.class);
		RoomParticipationService service = new RoomParticipationService(
			executor, Clock.fixed(NOW, ZoneOffset.UTC), new RoomOptimisticLockRetrier());
		OptimisticLockException third = new OptimisticLockException("third");
		org.mockito.Mockito.when(executor.participate(42L, 7L, NOW))
			.thenThrow(new OptimisticLockException("first"))
			.thenThrow(new OptimisticLockException("second"))
			.thenThrow(third);
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			BusinessException exception = assertThrows(
				BusinessException.class, () -> service.participate(42L, 7L));

			assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exception.getErrorCode());
			assertSame(third, exception.getCause());
			assertRetryLogs(appender);
		} finally {
			detachLogAppender(appender);
		}
	}

	@Test
	void 신규_참가는_ACTIVE_관계와_카운터를_저장한다() {
		long hostUserId = insertUser("host-new@example.com", "방장");
		long participantUserId = insertUser("participant-new@example.com", "참가자");
		Room room = createRoom(hostUserId, 2, NOW.plusSeconds(3600));

		RoomParticipationResponse response = roomParticipationService.participate(participantUserId, room.getId());

		assertEquals(room.getId(), response.roomId());
		assertEquals(ParticipationStatus.ACTIVE, response.participationStatus());
		assertEquals(RoomStatus.RECRUITING, response.roomStatus());
		assertEquals(2, response.participantCount());
		assertEquals(1, response.remainingRecruitmentSeats());
		assertEquals(
			1, roomRepository.findById(room.getId()).orElseThrow().getActiveParticipantCount());
		assertEquals(
			ParticipationStatus.ACTIVE,
			participationRepository
				.findByRoomIdAndUserId(room.getId(), participantUserId)
				.orElseThrow()
				.getStatus());
		assertEquals(
			NOW,
			participationRepository
				.findByRoomIdAndUserId(room.getId(), participantUserId)
				.orElseThrow()
				.getJoinedAt());
	}

	@Test
	void 취소된_관계는_새_행_없이_재활성화한다() {
		long hostUserId = insertUser("host-rejoin@example.com", "방장");
		long participantUserId = insertUser("participant-rejoin@example.com", "참가자");
		Room room = createRoom(hostUserId, 2, NOW.plusSeconds(3600));
		insertCanceledParticipation(room.getId(), participantUserId);

		roomParticipationService.participate(participantUserId, room.getId());

		assertEquals(
			1,
			jdbcTemplate.queryForObject(
				"select count(*) from participations where room_id = ? and user_id = ?",
				Integer.class,
				room.getId(),
				participantUserId));
		assertEquals(
			ParticipationStatus.ACTIVE,
			participationRepository
				.findByRoomIdAndUserId(room.getId(), participantUserId)
				.orElseThrow()
				.getStatus());
		assertEquals(
			NOW,
			participationRepository
				.findByRoomIdAndUserId(room.getId(), participantUserId)
				.orElseThrow()
				.getJoinedAt());
		assertEquals(
			null,
			jdbcTemplate.queryForObject(
				"select canceled_at from participations where room_id = ? and user_id = ?",
				Instant.class,
				room.getId(),
				participantUserId));
	}

	@Test
	void 마지막_모집_좌석_참가는_방을_CLOSED로_전이한다() {
		long hostUserId = insertUser("host-last@example.com", "방장");
		long participantUserId = insertUser("participant-last@example.com", "참가자");
		Room room = createRoom(hostUserId, 1, NOW.plusSeconds(3600));

		RoomParticipationResponse response = roomParticipationService.participate(participantUserId, room.getId());

		assertEquals(RoomStatus.CLOSED, response.roomStatus());
		assertEquals(0, response.remainingRecruitmentSeats());
		assertEquals(
			RoomStatus.CLOSED, roomRepository.findById(room.getId()).orElseThrow().getStatus());
	}

	@Test
	void 주최자와_활성_참가자는_ALREADY_PARTICIPATING이다() {
		long hostUserId = insertUser("host-active@example.com", "방장");
		long participantUserId = insertUser("participant-active@example.com", "참가자");
		Room room = createRoom(hostUserId, 2, NOW.plusSeconds(3600));
		roomParticipationService.participate(participantUserId, room.getId());

		assertError(
			ErrorCode.ALREADY_PARTICIPATING,
			() -> roomParticipationService.participate(hostUserId, room.getId()));
		assertError(
			ErrorCode.ALREADY_PARTICIPATING,
			() -> roomParticipationService.participate(participantUserId, room.getId()));
	}

	@Test
	void 모집_정원과_시간_경계는_계약_오류를_반환한다() {
		long hostUserId = insertUser("host-capacity@example.com", "방장");
		long firstUserId = insertUser("participant-capacity-1@example.com", "참가자1");
		long secondUserId = insertUser("participant-capacity-2@example.com", "참가자2");
		Room fullRoom = createRoom(hostUserId, 1, NOW.plusSeconds(3600));
		roomParticipationService.participate(firstUserId, fullRoom.getId());

		assertError(
			ErrorCode.CAPACITY_EXCEEDED,
			() -> roomParticipationService.participate(secondUserId, fullRoom.getId()));

		Room startedRoom = createRoom(hostUserId, 1, NOW);
		assertError(
			ErrorCode.ROOM_NOT_RECRUITING,
			() -> roomParticipationService.participate(secondUserId, startedRoom.getId()));
	}

	@Test
	void 취소와_종료_상태는_ACTIVE_관계와_정원_초과보다_우선한다() {
		long hostUserId = insertUser("host-final-priority@example.com", "방장");
		long participantUserId = insertUser("participant-final-priority@example.com", "참가자");
		Room room = createRoom(hostUserId, 1, NOW.plusSeconds(3600));
		insertActiveParticipation(room.getId(), participantUserId);
		jdbcTemplate.update(
			"update rooms set active_participant_count = capacity where id = ?", room.getId());

		for (RoomStatus status : java.util.List.of(RoomStatus.CANCELED, RoomStatus.FINISHED)) {
			jdbcTemplate.update(
				"update rooms set status = ? where id = ?", status.name(), room.getId());
			clearPersistenceContext();

			assertError(
				ErrorCode.ROOM_NOT_RECRUITING,
				() -> roomParticipationService.participate(participantUserId, room.getId()));
		}
	}

	@Test
	void 주최자와_ACTIVE_참가자는_정원_초과보다_우선한다() {
		long hostUserId = insertUser("host-active-priority@example.com", "방장");
		long participantUserId = insertUser("participant-active-priority@example.com", "참가자");
		Room room = createRoom(hostUserId, 1, NOW.plusSeconds(3600));
		insertActiveParticipation(room.getId(), participantUserId);
		jdbcTemplate.update(
			"update rooms set active_participant_count = capacity where id = ?", room.getId());
		clearPersistenceContext();

		assertError(
			ErrorCode.ALREADY_PARTICIPATING,
			() -> roomParticipationService.participate(hostUserId, room.getId()));
		assertError(
			ErrorCode.ALREADY_PARTICIPATING,
			() -> roomParticipationService.participate(participantUserId, room.getId()));
	}

	@Test
	void 정원_초과는_비모집_상태보다_우선한다() {
		long hostUserId = insertUser("host-capacity-priority@example.com", "방장");
		long participantUserId = insertUser("participant-capacity-priority@example.com", "참가자");
		Room room = createRoom(hostUserId, 1, NOW.plusSeconds(3600));
		jdbcTemplate.update(
			"update rooms set active_participant_count = capacity, status = 'CLOSED' where id = ?",
			room.getId());
		clearPersistenceContext();

		assertError(
			ErrorCode.CAPACITY_EXCEEDED,
			() -> roomParticipationService.participate(participantUserId, room.getId()));
	}

	private Room createRoom(long hostUserId, int capacity, Instant startsAt) {
		return roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"참가 테스트 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				startsAt,
				"홍대 장소",
				capacity));
	}

	private long insertUser(String email, String nickname) {
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'fixture-password-hash', ?, "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-28T00:00:00Z', "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-28T00:00:00Z')",
			email,
			nickname);
		return jdbcTemplate.queryForObject(
			"select id from users where email = ?", Long.class, email);
	}

	private void insertCanceledParticipation(long roomId, long userId) {
		jdbcTemplate.update(
			"insert into participations "
				+ "(room_id, user_id, status, joined_at, canceled_at, created_at, updated_at) "
				+ "values (?, ?, 'CANCELED', "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-27T23:00:00Z', "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-27T23:30:00Z', "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-27T23:00:00Z', "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-27T23:30:00Z')",
			roomId,
			userId);
	}

	private void insertActiveParticipation(long roomId, long userId) {
		jdbcTemplate.update(
			"insert into participations "
				+ "(room_id, user_id, status, joined_at, canceled_at, created_at, updated_at) "
				+ "values (?, ?, 'ACTIVE', "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-28T00:00:00Z', NULL, "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-28T00:00:00Z', "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-28T00:00:00Z')",
			roomId,
			userId);
	}

	private void clearPersistenceContext() {
		entityManager.clear();
	}

	private void assertError(ErrorCode expected, Runnable action) {
		BusinessException exception = assertThrows(BusinessException.class, action::run);
		assertEquals(expected, exception.getErrorCode());
	}

	private ListAppender<ILoggingEvent> attachLogAppender() {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(RoomOptimisticLockRetrier.class);
		logger.setLevel(Level.DEBUG);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(RoomOptimisticLockRetrier.class);
		logger.detachAppender(appender);
		logger.setLevel(null);
		appender.stop();
	}

	private void assertRetryLogs(ListAppender<ILoggingEvent> appender) {
		assertEquals(3, appender.list.size());
		assertEquals(Level.DEBUG, appender.list.get(0).getLevel());
		assertEquals(Level.DEBUG, appender.list.get(1).getLevel());
		assertEquals(Level.WARN, appender.list.get(2).getLevel());
		assertTrue(appender.list.stream().allMatch(
			event -> event.getFormattedMessage().contains("event=room_participation_retry roomId=7")));
		assertTrue(appender.list.get(0).getFormattedMessage().contains("attempt=2"));
		assertTrue(appender.list.get(1).getFormattedMessage().contains("attempt=3"));
		assertTrue(appender.list.get(2).getFormattedMessage().contains("attempt=3"));
		assertTrue(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null));
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
