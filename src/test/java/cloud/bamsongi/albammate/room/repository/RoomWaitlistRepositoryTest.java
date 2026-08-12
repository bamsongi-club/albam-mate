package cloud.bamsongi.albammate.room.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.room.entity.RoomWaitlist;
import cloud.bamsongi.albammate.room.entity.RoomWaitlistId;
import cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus;
import jakarta.persistence.EntityManager;

@SpringBootTest
@Transactional
class RoomWaitlistRepositoryTest {

	private static final Instant FIRST_REQUEST_TIME = Instant.parse("2026-08-04T00:00:00Z");
	private static final Instant SECOND_REQUEST_TIME = FIRST_REQUEST_TIME.plusSeconds(60);

	@Autowired
	private RoomWaitlistRepository roomWaitlistRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private EntityManager entityManager;

	private long roomId;
	private long firstUserId;
	private long secondUserId;
	private long thirdUserId;

	@BeforeEach
	void setUp() {
		long hostUserId = insertUser("waitlist-host@example.com");
		firstUserId = insertUser("waitlist-first@example.com");
		secondUserId = insertUser("waitlist-second@example.com");
		thirdUserId = insertUser("waitlist-third@example.com");
		roomId = insertRoom(hostUserId);
	}

	@Test
	void 신규_저장은_INSERT이고_같은_PK_저장은_UPDATE로_바뀌지_않는다() {
		RoomWaitlist first = RoomWaitlist.create(roomId, firstUserId, 1L, FIRST_REQUEST_TIME);
		roomWaitlistRepository.saveAndFlush(first);

		RoomWaitlist duplicate = RoomWaitlist.create(roomId, firstUserId, 2L, SECOND_REQUEST_TIME);
		assertThrows(
			DataIntegrityViolationException.class,
			() -> roomWaitlistRepository.saveAndFlush(duplicate));

		assertEquals(
			1L,
			jdbcTemplate.queryForObject(
				"select queue_order from room_waitlists where room_id = ? and user_id = ?",
				Long.class,
				roomId,
				firstUserId));
	}

	@Test
	void 기존_대기행_재저장은_INSERT를_재시도하지_않는다() {
		RoomWaitlist waitlist = RoomWaitlist.create(roomId, firstUserId, 1L, FIRST_REQUEST_TIME);
		roomWaitlistRepository.saveAndFlush(waitlist);
		assertFalse(waitlist.isNew());

		entityManager.clear();
		RoomWaitlist reloaded = roomWaitlistRepository.findById(new RoomWaitlistId(roomId, firstUserId))
			.orElseThrow();
		assertFalse(reloaded.isNew());

		roomWaitlistRepository.saveAndFlush(reloaded);

		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from room_waitlists where room_id = ? and user_id = ?", Integer.class, roomId,
			firstUserId));
	}

	@Test
	void 대기자_상태와_순번은_WAITING_앞선_행만_세어_반환한다() {
		saveWaiting(firstUserId, 10L, FIRST_REQUEST_TIME);
		saveWaiting(secondUserId, 20L, SECOND_REQUEST_TIME);
		roomWaitlistRepository.cancelWaiting(roomId, firstUserId, 10L, SECOND_REQUEST_TIME);
		entityManager.clear();

		RoomWaitlistStateProjection firstState = roomWaitlistRepository
			.findStateWithPositionByRoomIdAndUserId(roomId, firstUserId)
			.orElseThrow();
		RoomWaitlistStateProjection secondState = roomWaitlistRepository
			.findStateWithPositionByRoomIdAndUserId(roomId, secondUserId)
			.orElseThrow();

		assertEquals(RoomWaitlistStatus.CANCELED, firstState.getStatus());
		assertNull(firstState.getPosition());
		assertEquals(1L, secondState.getPosition());
	}

	@Test
	void 첫_승격_후보는_가장_작은_현재_WAITING_순번이다() {
		saveWaiting(firstUserId, 20L, FIRST_REQUEST_TIME);
		saveWaiting(secondUserId, 10L, SECOND_REQUEST_TIME);

		RoomWaitlistCandidateProjection candidate = roomWaitlistRepository
			.findFirstWaitingByRoomId(roomId)
			.orElseThrow();

		assertEquals(secondUserId, candidate.getUserId());
		assertEquals(10L, candidate.getQueueOrder());
	}

	@Test
	void 조건부_전이는_허용된_출발상태에서만_순번과_시각을_보존한다() {
		saveWaiting(firstUserId, 10L, FIRST_REQUEST_TIME);
		saveWaiting(secondUserId, 20L, FIRST_REQUEST_TIME);

		assertEquals(0, roomWaitlistRepository.promoteWaiting(roomId, firstUserId, 9L, SECOND_REQUEST_TIME));
		assertEquals(1, roomWaitlistRepository.promoteWaiting(roomId, firstUserId, 10L, SECOND_REQUEST_TIME));
		assertEquals(0, roomWaitlistRepository.cancelWaiting(roomId, firstUserId, 10L, SECOND_REQUEST_TIME));
		assertEquals(1, roomWaitlistRepository.reactivateWaiting(roomId, firstUserId, 30L, SECOND_REQUEST_TIME));

		entityManager.clear();
		RoomWaitlist stored = roomWaitlistRepository.findById(new RoomWaitlistId(roomId, firstUserId))
			.orElseThrow();
		assertEquals(RoomWaitlistStatus.WAITING, stored.getStatus());
		assertEquals(30L, stored.getQueueOrder());
		assertEquals(FIRST_REQUEST_TIME, stored.getCreatedAt());
		assertEquals(SECOND_REQUEST_TIME, stored.getQueuedAt());
		assertEquals(SECOND_REQUEST_TIME, stored.getUpdatedAt());

		assertEquals(2, roomWaitlistRepository.expireAllWaiting(roomId, SECOND_REQUEST_TIME));
		entityManager.clear();
		RoomWaitlist expired = roomWaitlistRepository.findById(new RoomWaitlistId(roomId, firstUserId))
			.orElseThrow();
		assertEquals(RoomWaitlistStatus.EXPIRED, expired.getStatus());
		assertEquals(30L, expired.getQueueOrder());
		assertEquals(SECOND_REQUEST_TIME, expired.getQueuedAt());
		assertEquals(FIRST_REQUEST_TIME, expired.getCreatedAt());
		assertEquals(SECOND_REQUEST_TIME, expired.getUpdatedAt());
		saveWaiting(thirdUserId, 40L, FIRST_REQUEST_TIME);
		assertEquals(1, roomWaitlistRepository.cancelAllWaiting(roomId, SECOND_REQUEST_TIME));
		entityManager.clear();
		RoomWaitlist canceled = roomWaitlistRepository.findById(new RoomWaitlistId(roomId, thirdUserId))
			.orElseThrow();
		assertEquals(RoomWaitlistStatus.ROOM_CANCELED, canceled.getStatus());
		assertEquals(40L, canceled.getQueueOrder());
		assertEquals(FIRST_REQUEST_TIME, canceled.getQueuedAt());
		assertEquals(FIRST_REQUEST_TIME, canceled.getCreatedAt());
		assertEquals(SECOND_REQUEST_TIME, canceled.getUpdatedAt());
		assertFalse(roomWaitlistRepository.findFirstWaitingByRoomId(roomId).isPresent());
	}

	@Test
	void 취소와_승격_대기행만_재신청할_수_있다() {
		saveWaiting(firstUserId, 10L, FIRST_REQUEST_TIME);
		assertEquals(1, roomWaitlistRepository.cancelWaiting(roomId, firstUserId, 10L, SECOND_REQUEST_TIME));
		assertEquals(1, roomWaitlistRepository.reactivateWaiting(roomId, firstUserId, 20L, SECOND_REQUEST_TIME));
		assertEquals(1, roomWaitlistRepository.cancelAllWaiting(roomId, SECOND_REQUEST_TIME));
		assertEquals(0, roomWaitlistRepository.reactivateWaiting(roomId, firstUserId, 30L, SECOND_REQUEST_TIME));

		saveWaiting(secondUserId, 40L, FIRST_REQUEST_TIME);
		assertEquals(1, roomWaitlistRepository.promoteWaiting(roomId, secondUserId, 40L, SECOND_REQUEST_TIME));
		assertEquals(1, roomWaitlistRepository.reactivateWaiting(roomId, secondUserId, 50L, SECOND_REQUEST_TIME));
		assertEquals(1, roomWaitlistRepository.expireAllWaiting(roomId, SECOND_REQUEST_TIME));
		assertEquals(0, roomWaitlistRepository.reactivateWaiting(roomId, secondUserId, 60L, SECOND_REQUEST_TIME));
	}

	@Test
	void T2_이전_순번의_취소와_승격은_재신청된_WAITING을_전이하지_못한다() {
		saveWaiting(firstUserId, 10L, FIRST_REQUEST_TIME);
		assertEquals(1, roomWaitlistRepository.cancelWaiting(roomId, firstUserId, 10L, SECOND_REQUEST_TIME));
		assertEquals(1, roomWaitlistRepository.reactivateWaiting(roomId, firstUserId, 20L, SECOND_REQUEST_TIME));

		assertEquals(0, roomWaitlistRepository.cancelWaiting(roomId, firstUserId, 10L, SECOND_REQUEST_TIME));
		assertEquals(0, roomWaitlistRepository.promoteWaiting(roomId, firstUserId, 10L, SECOND_REQUEST_TIME));

		entityManager.clear();
		RoomWaitlist stored = roomWaitlistRepository.findById(new RoomWaitlistId(roomId, firstUserId))
			.orElseThrow();
		assertEquals(RoomWaitlistStatus.WAITING, stored.getStatus());
		assertEquals(20L, stored.getQueueOrder());
	}

	@Test
	void 순번은_전역_sequence에서_명시적으로_발급한다() {
		long firstQueueOrder = roomWaitlistRepository.getNextQueueOrder();
		long secondQueueOrder = roomWaitlistRepository.getNextQueueOrder();

		assertEquals(firstQueueOrder + 1, secondQueueOrder);
	}

	@Test
	void 대기열_저장소는_승인된_조회_순번_조건부_전이_메서드만_선언한다() {
		Set<String> declaredMethodNames = Arrays.stream(RoomWaitlistRepository.class.getDeclaredMethods())
			.map(method -> method.getName())
			.collect(java.util.stream.Collectors.toSet());

		assertEquals(
			Set.of(
				"findStateWithPositionByRoomIdAndUserId",
				"findFirstWaitingByRoomId",
				"findWaitingRoomIdsByUserIdAndRoomIds",
				"getNextQueueOrder",
				"cancelWaiting",
				"promoteWaiting",
				"expireAllWaiting",
				"cancelAllWaiting",
				"reactivateWaiting"),
			declaredMethodNames);
		assertEquals(
			1,
			Arrays.stream(RoomRepository.class.getDeclaredMethods())
				.filter(method -> method.getName().equals("claimVersion"))
				.count());
	}

	private void saveWaiting(long userId, long queueOrder, Instant requestTime) {
		roomWaitlistRepository.saveAndFlush(
			RoomWaitlist.create(roomId, userId, queueOrder, requestTime));
	}

	private long insertUser(String email) {
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?)",
			email,
			email,
			FIRST_REQUEST_TIME,
			FIRST_REQUEST_TIME);
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	private long insertRoom(long hostUserId) {
		jdbcTemplate.update(
			"""
				insert into rooms (
				    host_user_id, room_type, title, experience_level, is_rulemaster_led, region, capacity,
				    active_participant_count, start_at, place, status, version, created_at, updated_at)
				values (?, 'PERSON_FOCUSED', '대기열 테스트 방', 'ALL_LEVELS', false, '홍대', 4, 0, ?, '테스트 장소',
				        'RECRUITING', 0, ?, ?)
				""",
			hostUserId,
			FIRST_REQUEST_TIME.plusSeconds(3600),
			FIRST_REQUEST_TIME,
			FIRST_REQUEST_TIME);
		return jdbcTemplate.queryForObject("select id from rooms where host_user_id = ?", Long.class, hostUserId);
	}
}
