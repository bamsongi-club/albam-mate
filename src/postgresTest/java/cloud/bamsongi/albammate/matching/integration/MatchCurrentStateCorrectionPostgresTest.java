package cloud.bamsongi.albammate.matching.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.matching.service.query.MatchCurrentStateQueryCoordinator;
import cloud.bamsongi.albammate.matching.service.query.MatchCurrentStateReadService;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class, properties = "spring.task.scheduling.enabled=false")
class MatchCurrentStateCorrectionPostgresTest extends SharedPostgresIntegrationSupport {

	@Autowired
	private MatchCurrentStateQueryCoordinator primaryCurrentState;
	@MockitoSpyBean
	private MatchCurrentStateReadService currentStateReadService;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute("truncate table match_chat_messages, match_chat_rooms, match_proposal_members, "
			+ "match_proposals, match_party_participants, match_parties, match_requests, users restart identity cascade");
	}

	@Test
	void scheduler가_멈춰도_재시작한_인스턴스의_현재상태_조회가_due_ACTIVE를_보정하고_불안정하면_세번_재시도한다()
		throws Exception {
		long dueUserId = insertUser("due");
		long duePartyId = insertActiveParty(Instant.now().plusSeconds(3_599));
		insertParticipant(duePartyId, dueUserId);
		insertMatchChatRoom(duePartyId);

		String restartedState = MatchLifecycleConcurrencyPostgresTest.Match746IndependentApplicationProcess.runSingle(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
			MatchLifecycleConcurrencyPostgresTest.Match746IndependentApplicationProcess.command(
				"current", String.valueOf(dueUserId)));
		assertEquals("ACTIVE|" + duePartyId + "|1", restartedState);

		assertEquals(1, jdbcTemplate.queryForObject("""
			select count(*) from match_chat_messages message
			join match_chat_rooms room on room.id = message.match_chat_room_id
			where room.party_id = ? and message.system_event_key = 'CLOSES_IN_ONE_HOUR'
			""", Integer.class, duePartyId));

		long unstableUserId = insertUser("unstable");
		insertWaitingRequest(unstableUserId);
		long unstablePartyId = insertActiveParty(Instant.now().plusSeconds(86_400));
		insertParticipant(unstablePartyId, unstableUserId);
		clearInvocations(currentStateReadService);

		BusinessException exception = assertThrows(BusinessException.class,
			() -> primaryCurrentState.read(unstableUserId));

		assertEquals(ErrorCode.MATCH_CURRENT_STATE_NOT_STABLE, exception.getErrorCode());
		verify(currentStateReadService, times(3)).read(unstableUserId);
	}

	private long insertUser(String name) {
		Instant now = Instant.now();
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?) returning id",
			Long.class, "match-current-" + name + "-" + UUID.randomUUID() + "@example.com", name,
			Timestamp.from(now), Timestamp.from(now));
	}

	private long insertActiveParty(Instant closesAt) {
		Instant openedAt = closesAt.minusSeconds(86_400);
		return jdbcTemplate.queryForObject("""
			insert into match_parties (status, preparing_started_at, chat_opened_at, closes_at, created_at, updated_at)
			values ('ACTIVE', ?, ?, ?, ?, ?)
			returning id
			""", Long.class, Timestamp.from(openedAt), Timestamp.from(openedAt), Timestamp.from(closesAt),
			Timestamp.from(openedAt), Timestamp.from(openedAt));
	}

	private void insertParticipant(long partyId, long userId) {
		jdbcTemplate.update("""
			insert into match_party_participants (party_id, user_id, participant_ref, created_at)
			values (?, ?, ?, ?)
			""", partyId, userId, UUID.randomUUID(), Timestamp.from(Instant.now()));
	}

	private void insertMatchChatRoom(long partyId) {
		Instant now = Instant.now();
		jdbcTemplate.update("insert into match_chat_rooms (party_id, created_at, updated_at) values (?, ?, ?)",
			partyId, Timestamp.from(now), Timestamp.from(now));
	}

	private void insertWaitingRequest(long userId) {
		Instant now = Instant.now();
		jdbcTemplate.update("""
			insert into match_requests
			(user_id, min_party_size, max_party_size, status, queued_at, priority_since, created_at, updated_at)
			values (?, 1, 1, 'WAITING', ?, ?, ?, ?)
			""", userId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
	}
}
