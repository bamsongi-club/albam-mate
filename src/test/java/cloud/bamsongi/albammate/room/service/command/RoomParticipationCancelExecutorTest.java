package cloud.bamsongi.albammate.room.service.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import jakarta.persistence.EntityManager;

@SpringBootTest
@Import(RoomParticipationCancelExecutorTest.FixedClockConfiguration.class)
class RoomParticipationCancelExecutorTest {

	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

	@Autowired
	private RoomParticipationCancelService roomParticipationCancelService;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private ParticipationRepository participationRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private EntityManager entityManager;

	@Test
	void 없는_방의_서비스_통합_경로는_ROOM_NOT_FOUND로_종료한다() {
		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> roomParticipationCancelService.cancelParticipation(42L, 999_999L));

		assertEquals(ErrorCode.ROOM_NOT_FOUND, exception.getErrorCode());
	}

	@Test
	void 마지막_활성_참가를_취소하면_기존_행과_카운터를_갱신하고_모집을_재개한다() {
		long hostUserId = insertUser("cancel-host@example.com", "방장");
		long participantUserId = insertUser("cancel-member@example.com", "참가자");
		Room room = createRoom(hostUserId, 1, NOW.plusSeconds(3600));
		Participation participation = participationRepository.saveAndFlush(
			Participation.createActive(room, participantUserId, NOW.minusSeconds(60)));
		room.addActiveParticipant();
		roomRepository.saveAndFlush(room);

		RoomParticipationResponse response = roomParticipationCancelService.cancelParticipation(participantUserId,
			room.getId());

		assertEquals(ParticipationStatus.CANCELED, response.participationStatus());
		assertEquals(RoomStatus.RECRUITING, response.roomStatus());
		assertEquals(1, response.participantCount());
		assertEquals(1, response.remainingRecruitmentSeats());
		assertEquals(
			1,
			jdbcTemplate.queryForObject(
				"select count(*) from participations where room_id = ? and user_id = ?",
				Integer.class,
				room.getId(),
				participantUserId));
		Participation canceledParticipation = participationRepository
			.findByRoomIdAndUserId(room.getId(), participantUserId)
			.orElseThrow();
		assertEquals(participation.getId(), canceledParticipation.getId());
		assertEquals(ParticipationStatus.CANCELED, canceledParticipation.getStatus());
		assertEquals(NOW, canceledParticipation.getCanceledAt());
		assertEquals(
			0, roomRepository.findById(room.getId()).orElseThrow().getActiveParticipantCount());
		assertEquals(
			RoomStatus.RECRUITING,
			roomRepository.findById(room.getId()).orElseThrow().getStatus());
	}

	@Test
	void 취소된_참가_관계는_활성_참가가_아니므로_참가_관계를_찾지_못한_오류를_반환한다() {
		long hostUserId = insertUser("canceled-host@example.com", "방장");
		long participantUserId = insertUser("canceled-member@example.com", "참가자");
		Room room = createRoom(hostUserId, 1, NOW.plusSeconds(3600));
		Instant canceledAt = NOW.minusSeconds(30);
		Participation participation = Participation.createActive(room, participantUserId, NOW.minusSeconds(60));
		participation.cancel(canceledAt);
		participationRepository.saveAndFlush(participation);

		assertError(
			ErrorCode.PARTICIPATION_NOT_FOUND,
			() -> roomParticipationCancelService.cancelParticipation(participantUserId, room.getId()));

		clearPersistenceContext();
		Participation canceledParticipation = participationRepository
			.findByRoomIdAndUserId(room.getId(), participantUserId)
			.orElseThrow();
		assertEquals(ParticipationStatus.CANCELED, canceledParticipation.getStatus());
		assertEquals(canceledAt, canceledParticipation.getCanceledAt());
		assertEquals(0, roomRepository.findById(room.getId()).orElseThrow().getActiveParticipantCount());
	}

	@Test
	void 오류_우선순위에_따라_주최자와_없는_관계와_시작_이후를_거절한다() {
		long hostUserId = insertUser("error-host@example.com", "방장");
		long participantUserId = insertUser("error-member@example.com", "참가자");
		Room futureRoom = createRoom(hostUserId, 1, NOW.plusSeconds(3600));

		assertError(
			ErrorCode.FORBIDDEN,
			() -> roomParticipationCancelService.cancelParticipation(
				hostUserId, futureRoom.getId()));
		assertError(
			ErrorCode.PARTICIPATION_NOT_FOUND,
			() -> roomParticipationCancelService.cancelParticipation(
				participantUserId, futureRoom.getId()));

		Room startedRoom = createRoom(hostUserId, 1, NOW);
		assertError(
			ErrorCode.FORBIDDEN,
			() -> roomParticipationCancelService.cancelParticipation(hostUserId, startedRoom.getId()));
		assertError(
			ErrorCode.PARTICIPATION_NOT_FOUND,
			() -> roomParticipationCancelService.cancelParticipation(participantUserId, startedRoom.getId()));

		participationRepository.saveAndFlush(
			Participation.createActive(startedRoom, participantUserId, NOW.minusSeconds(60)));
		jdbcTemplate.update(
			"update rooms set active_participant_count = 1 where id = ?", startedRoom.getId());
		clearPersistenceContext();

		assertError(
			ErrorCode.INVALID_ROOM_STATUS_TRANSITION,
			() -> roomParticipationCancelService.cancelParticipation(
				participantUserId, startedRoom.getId()));
		clearPersistenceContext();
		assertEquals(
			ParticipationStatus.ACTIVE,
			participationRepository
				.findByRoomIdAndUserId(startedRoom.getId(), participantUserId)
				.orElseThrow()
				.getStatus());
		assertEquals(
			1,
			roomRepository
				.findById(startedRoom.getId())
				.orElseThrow()
				.getActiveParticipantCount());
		assertEquals(
			RoomStatus.RECRUITING,
			roomRepository.findById(startedRoom.getId()).orElseThrow().getStatus());
	}

	private Room createRoom(long hostUserId, int capacity, Instant startAt) {
		return roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"취소 테스트 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				startAt,
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

	private void assertError(ErrorCode expected, Runnable action) {
		BusinessException exception = assertThrows(BusinessException.class, action::run);
		assertEquals(expected, exception.getErrorCode());
	}

	private void clearPersistenceContext() {
		entityManager.clear();
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
