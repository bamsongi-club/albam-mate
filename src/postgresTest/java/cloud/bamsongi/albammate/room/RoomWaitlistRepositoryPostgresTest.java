package cloud.bamsongi.albammate.room;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.room.entity.RoomWaitlist;
import cloud.bamsongi.albammate.room.entity.RoomWaitlistId;
import cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistCandidateProjection;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistStateProjection;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

@Testcontainers
@SpringBootTest
class RoomWaitlistRepositoryPostgresTest extends SharedPostgresIntegrationSupport {

	private static final Instant FIRST_REQUEST_TIME = Instant.parse("2026-08-04T00:00:00Z");

	@Autowired
	private RoomWaitlistRepository roomWaitlistRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private long roomId;
	private long firstUserId;
	private long secondUserId;

	@BeforeEach
	void setUp() {
		long hostUserId = insertUser("postgres-waitlist-host@example.com");
		firstUserId = insertUser("postgres-waitlist-first@example.com");
		secondUserId = insertUser("postgres-waitlist-second@example.com");
		roomId = insertRoom(hostUserId);
	}

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute("truncate table room_waitlists, rooms, users restart identity cascade");
	}

	@Test
	void PostgreSQL_native_SQL은_상태와_position을_한_snapshot으로_조회하고_FIFO_후보를_고른다() {
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(roomId, firstUserId, 10L, FIRST_REQUEST_TIME));
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(roomId, secondUserId, 20L, FIRST_REQUEST_TIME));

		RoomWaitlistStateProjection state = roomWaitlistRepository
			.findStateWithPositionByRoomIdAndUserId(roomId, secondUserId)
			.orElseThrow();
		RoomWaitlistCandidateProjection candidate = roomWaitlistRepository
			.findFirstWaitingByRoomId(roomId)
			.orElseThrow();

		assertEquals(RoomWaitlistStatus.WAITING, state.getStatus());
		assertEquals(2L, state.getPosition());
		assertEquals(firstUserId, candidate.getUserId());
		assertEquals(10L, candidate.getQueueOrder());
	}

	@Test
	void T2_PostgreSQL_한_statement로_첫_WAITING을_조건부_PROMOTED로_전이하고_FIFO_결과를_반환한다() {
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(roomId, firstUserId, 10L, FIRST_REQUEST_TIME));
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(roomId, secondUserId, 20L, FIRST_REQUEST_TIME));

		RoomWaitlistRepository.FirstWaitingPromotionProjection promotion = roomWaitlistRepository
			.promoteFirstWaitingByRoomId(roomId, FIRST_REQUEST_TIME.plusSeconds(60))
			.orElseThrow();

		assertEquals(firstUserId, promotion.getUserId());
		assertEquals(10L, promotion.getQueueOrder());
		assertEquals(true, promotion.getPromoted());
		assertEquals(
			RoomWaitlistStatus.PROMOTED,
			roomWaitlistRepository.findById(new RoomWaitlistId(roomId, firstUserId)).orElseThrow().getStatus());
		assertEquals(
			RoomWaitlistStatus.WAITING,
			roomWaitlistRepository.findById(new RoomWaitlistId(roomId, secondUserId)).orElseThrow().getStatus());
	}

	private long insertUser(String email) {
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?)",
			email,
			email,
			Timestamp.from(FIRST_REQUEST_TIME),
			Timestamp.from(FIRST_REQUEST_TIME));
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	private long insertRoom(long hostUserId) {
		jdbcTemplate.update(
			"""
				insert into rooms (
				    host_user_id, room_type, title, experience_level, is_rulemaster_led, region, capacity,
				    active_participant_count, start_at, place, status, version, created_at, updated_at)
				values (?, 'PERSON_FOCUSED', 'PostgreSQL 대기열 방', 'ALL_LEVELS', false, '홍대', 4, 0, ?, '테스트 장소',
				        'RECRUITING', 0, ?, ?)
				""",
			hostUserId,
			Timestamp.from(FIRST_REQUEST_TIME.plusSeconds(3600)),
			Timestamp.from(FIRST_REQUEST_TIME),
			Timestamp.from(FIRST_REQUEST_TIME));
		return jdbcTemplate.queryForObject("select id from rooms where host_user_id = ?", Long.class, hostUserId);
	}
}
