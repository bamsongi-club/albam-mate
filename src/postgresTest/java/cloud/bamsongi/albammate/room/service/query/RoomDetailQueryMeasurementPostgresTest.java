package cloud.bamsongi.albammate.room.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

@Testcontainers
@SpringBootTest(properties = {
	"spring.task.scheduling.enabled=false",
	"app.notification.relay.enabled=false",
	"app.chat.retention.enabled=false"
})
class RoomDetailQueryMeasurementPostgresTest {

	private static final Instant FIXTURE_TIME = Instant.parse("2099-01-01T10:00:00Z");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4")
		.withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements");

	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private ParticipationRepository participationRepository;
	@Autowired
	private RoomDetailReadService roomDetailReadService;

	@BeforeEach
	void setUp() {
		jdbcTemplate.execute("create extension if not exists pg_stat_statements");
	}

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute("truncate table room_waitlists, participations, rooms, users restart identity cascade");
	}

	@Test
	void 요청자_유형과_ACTIVE_참가자_수별_상세_조회_SQL_왕복과_처리_행을_기록한다() {
		Room oneParticipantRoom = roomWithActiveParticipants(1);

		QueryMeasurement publicMeasurement = measure(oneParticipantRoom.getId(), null);
		assertEquals(1L, publicMeasurement.dataReadCalls(), "비로그인 공개 상세 SQL 왕복 수");
		assertEquals(1L, publicMeasurement.dataReadRows(), "비로그인 공개 상세 처리 행 수");
		assertEquals(0L, publicMeasurement.fullActiveListCalls(), "공개 상세 ACTIVE 전체 목록 조회 수");

		QueryMeasurement hostOneParticipantMeasurement = measure(oneParticipantRoom.getId(),
			oneParticipantRoom.getHostUserId());
		assertEquals(2L, hostOneParticipantMeasurement.dataReadCalls(), "주최자 1명 fixture SQL 왕복 수");
		assertEquals(2L, hostOneParticipantMeasurement.dataReadRows(), "주최자 1명 fixture 처리 행 수");
		assertEquals(1L, hostOneParticipantMeasurement.fullActiveListCalls(), "주최자 ACTIVE 전체 목록 조회 수");

		long activeParticipantUserId = participationRepository
			.findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(oneParticipantRoom.getId(),
				cloud.bamsongi.albammate.room.enums.ParticipationStatus.ACTIVE)
			.getFirst()
			.getUserId();
		QueryMeasurement activeParticipantMeasurement = measure(oneParticipantRoom.getId(), activeParticipantUserId);
		assertEquals(3L, activeParticipantMeasurement.dataReadCalls(), "ACTIVE 참가자 1명 fixture SQL 왕복 수");
		assertEquals(3L, activeParticipantMeasurement.dataReadRows(), "ACTIVE 참가자 1명 fixture 처리 행 수");
		assertEquals(1L, activeParticipantMeasurement.fullActiveListCalls(), "ACTIVE 참가자 전체 목록 조회 수");

		long waitingUserId = user("waiting@example.com");
		createWaiting(oneParticipantRoom.getId(), waitingUserId);
		QueryMeasurement waitingMeasurement = measure(oneParticipantRoom.getId(), waitingUserId);
		assertEquals(3L, waitingMeasurement.dataReadCalls(), "WAITING 요청자 SQL 왕복 수");
		assertEquals(2L, waitingMeasurement.dataReadRows(), "WAITING 요청자 처리 행 수");
		assertEquals(0L, waitingMeasurement.fullActiveListCalls(), "WAITING 요청자 전체 목록 조회 수");

		long unrelatedUserId = user("unrelated@example.com");
		QueryMeasurement unrelatedMeasurement = measure(oneParticipantRoom.getId(), unrelatedUserId);
		assertEquals(3L, unrelatedMeasurement.dataReadCalls(), "무관계 로그인 요청자 SQL 왕복 수");
		assertEquals(1L, unrelatedMeasurement.dataReadRows(), "무관계 로그인 요청자 처리 행 수");
		assertEquals(0L, unrelatedMeasurement.fullActiveListCalls(), "무관계 로그인 요청자 전체 목록 조회 수");

		long canceledUserId = user("canceled@example.com");
		Participation canceledParticipation = Participation.createActive(oneParticipantRoom, canceledUserId,
			FIXTURE_TIME);
		canceledParticipation.cancel(FIXTURE_TIME.plusSeconds(1));
		participationRepository.saveAndFlush(canceledParticipation);
		QueryMeasurement canceledMeasurement = measure(oneParticipantRoom.getId(), canceledUserId);
		assertEquals(3L, canceledMeasurement.dataReadCalls(), "CANCELED 관계 요청자 SQL 왕복 수");
		assertEquals(2L, canceledMeasurement.dataReadRows(), "CANCELED 관계 요청자 처리 행 수");
		assertEquals(0L, canceledMeasurement.fullActiveListCalls(), "CANCELED 관계 전체 목록 조회 수");

		clearFixture();
		Room tenParticipantRoom = roomWithActiveParticipants(10);
		QueryMeasurement hostTenParticipantMeasurement = measure(tenParticipantRoom.getId(),
			tenParticipantRoom.getHostUserId());
		assertEquals(hostOneParticipantMeasurement.dataReadCalls(), hostTenParticipantMeasurement.dataReadCalls(),
			"ACTIVE 참가자 1명·10명 주최자 상세 SQL 왕복 수");
		assertEquals(11L, hostTenParticipantMeasurement.dataReadRows(), "주최자 10명 fixture 처리 행 수");
		assertEquals(1L, hostTenParticipantMeasurement.fullActiveListCalls(), "주최자 10명 ACTIVE 전체 목록 조회 수");

		long activeTenParticipantUserId = participationRepository
			.findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(tenParticipantRoom.getId(),
				cloud.bamsongi.albammate.room.enums.ParticipationStatus.ACTIVE)
			.getFirst()
			.getUserId();
		QueryMeasurement activeTenParticipantMeasurement = measure(
			tenParticipantRoom.getId(), activeTenParticipantUserId);
		assertEquals(activeParticipantMeasurement.dataReadCalls(), activeTenParticipantMeasurement.dataReadCalls(),
			"ACTIVE 참가자 1명·10명 상세 SQL 왕복 수");
		assertEquals(12L, activeTenParticipantMeasurement.dataReadRows(), "ACTIVE 참가자 10명 fixture 처리 행 수");
		assertEquals(1L, activeTenParticipantMeasurement.fullActiveListCalls(), "ACTIVE 참가자 10명 전체 목록 조회 수");

		long canceledFinalNonhostUserId = user("canceled-final-nonhost@example.com");
		Room canceledRoom = roomWithActiveParticipants(0, "canceled-final");
		canceledRoom.cancel();
		roomRepository.saveAndFlush(canceledRoom);
		QueryMeasurement canceledFinalMeasurement = measure(canceledRoom.getId(), canceledFinalNonhostUserId);
		assertEquals(2L, canceledFinalMeasurement.dataReadCalls(), "CANCELED 최종 비관계 SQL 왕복 수");
		assertEquals(1L, canceledFinalMeasurement.dataReadRows(), "CANCELED 최종 비관계 처리 행 수");
		assertEquals(0L, canceledFinalMeasurement.fullActiveListCalls(), "CANCELED 최종 전체 목록 조회 수");

		long finishedFinalNonhostUserId = user("finished-final-nonhost@example.com");
		Room finishedRoom = roomWithActiveParticipants(0, "finished-final");
		finishedRoom.reconcileStateAt(FIXTURE_TIME.plus(Room.AUTOMATIC_FINISH_AFTER_START));
		roomRepository.saveAndFlush(finishedRoom);
		QueryMeasurement finishedFinalMeasurement = measure(finishedRoom.getId(), finishedFinalNonhostUserId);
		assertEquals(2L, finishedFinalMeasurement.dataReadCalls(), "FINISHED 최종 비관계 SQL 왕복 수");
		assertEquals(1L, finishedFinalMeasurement.dataReadRows(), "FINISHED 최종 비관계 처리 행 수");
		assertEquals(0L, finishedFinalMeasurement.fullActiveListCalls(), "FINISHED 최종 전체 목록 조회 수");

		record("public", publicMeasurement);
		record("host-active-1", hostOneParticipantMeasurement);
		record("active-participant-1", activeParticipantMeasurement);
		record("waiting", waitingMeasurement);
		record("logged-unrelated", unrelatedMeasurement);
		record("canceled-participation", canceledMeasurement);
		record("host-active-10", hostTenParticipantMeasurement);
		record("active-participant-10", activeTenParticipantMeasurement);
		record("canceled-final-nonhost", canceledFinalMeasurement);
		record("finished-final-nonhost", finishedFinalMeasurement);
	}

	private QueryMeasurement measure(long roomId, Long currentUserId) {
		jdbcTemplate.execute("select pg_stat_statements_reset()");
		roomDetailReadService.findRoomDetail(roomId, currentUserId);
		return jdbcTemplate
			.query(
				"""
					select query, calls, rows
					from pg_stat_statements
					where dbid = (select oid from pg_database where datname = current_database())
					  and query like 'select %'
					  and query not like '%pg_stat_statements%'
					  and (query like '%from rooms%' or query like '%from participations%' or query like '%from room_waitlists%')
					""",
				(resultSet, rowNumber) -> new StatementMeasurement(
					resultSet.getString("query"), resultSet.getLong("calls"), resultSet.getLong("rows")))
			.stream()
			.collect(QueryMeasurement::new, QueryMeasurement::add, QueryMeasurement::merge);
	}

	private Room roomWithActiveParticipants(int activeParticipantCount) {
		return roomWithActiveParticipants(activeParticipantCount, "default");
	}

	private Room roomWithActiveParticipants(int activeParticipantCount, String fixtureName) {
		long hostUserId = user("host-" + fixtureName + "-" + activeParticipantCount + "@example.com");
		Room room = roomRepository.saveAndFlush(Room.create(
			hostUserId,
			RoomType.PERSON_FOCUSED,
			"ROOM-558 상세 조회 측정 " + fixtureName + " " + activeParticipantCount,
			null,
			null,
			ExperienceLevel.ALL_LEVELS,
			false,
			FIXTURE_TIME,
			"서울",
			10));
		for (int index = 0; index < activeParticipantCount; index++) {
			long userId = user(
				"participant-" + fixtureName + "-" + activeParticipantCount + "-" + index + "@example.com");
			participationRepository.save(Participation.createActive(room, userId, FIXTURE_TIME.plusSeconds(index)));
		}
		participationRepository.flush();
		return room;
	}

	private long user(String email) {
		return jdbcTemplate.queryForObject("""
			insert into users (email, password_hash, nickname, created_at, updated_at)
			values (?, 'hash', '사용자', ?, ?) returning id
			""", Long.class, email, FIXTURE_TIME.atOffset(ZoneOffset.UTC), FIXTURE_TIME.atOffset(ZoneOffset.UTC));
	}

	private void createWaiting(long roomId, long userId) {
		jdbcTemplate.update("""
			insert into room_waitlists (room_id, user_id, status, queue_order, queued_at, created_at, updated_at)
			values (?, ?, 'WAITING', nextval('room_waitlist_queue_order_seq'), ?, ?, ?)
			""", roomId, userId, FIXTURE_TIME.atOffset(ZoneOffset.UTC), FIXTURE_TIME.atOffset(ZoneOffset.UTC),
			FIXTURE_TIME.atOffset(ZoneOffset.UTC));
	}

	private void clearFixture() {
		jdbcTemplate.execute("truncate table room_waitlists, participations, rooms, users restart identity cascade");
	}

	private void record(String requesterType, QueryMeasurement measurement) {
		System.out.println(
			"ROOM-558_DETAIL_QUERY requester=" + requesterType
				+ " sqlRoundTrips=" + measurement.dataReadCalls()
				+ " processedRows=" + measurement.dataReadRows()
				+ " fullActiveListCalls=" + measurement.fullActiveListCalls());
	}

	private record StatementMeasurement(String query, long calls, long rows) {
	}

	private static final class QueryMeasurement {

		private long dataReadCalls;
		private long dataReadRows;
		private long fullActiveListCalls;

		private void add(StatementMeasurement statement) {
			dataReadCalls += statement.calls();
			dataReadRows += statement.rows();
			if (statement.query().contains("from participations") && statement.query().contains("order by")) {
				fullActiveListCalls += statement.calls();
			}
		}

		private void merge(QueryMeasurement other) {
			dataReadCalls += other.dataReadCalls;
			dataReadRows += other.dataReadRows;
			fullActiveListCalls += other.fullActiveListCalls;
		}

		private long dataReadCalls() {
			return dataReadCalls;
		}

		private long dataReadRows() {
			return dataReadRows;
		}

		private long fullActiveListCalls() {
			return fullActiveListCalls;
		}
	}
}
