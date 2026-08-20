package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
import cloud.bamsongi.albammate.matching.dto.CurrentMatchStateResponse;
import cloud.bamsongi.albammate.matching.service.query.MatchCurrentStateQueryCoordinator;
import cloud.bamsongi.albammate.matching.service.query.MatchCurrentStateReadService;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class MatchCurrentStateSnapshotPostgresTest extends SharedPostgresIntegrationSupport {

	private static final Instant EXPIRED_SNAPSHOT_FIXTURE_TIME = Instant.parse("2020-01-01T00:00:00Z");

	@Autowired
	private MatchCurrentStateQueryCoordinator currentStateQueryCoordinator;
	@MockitoSpyBean
	private MatchCurrentStateReadService currentStateReadService;
	@MockitoSpyBean
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute(
			"truncate table match_chat_messages, match_chat_rooms, match_idempotency_records, match_proposal_members, match_proposals, match_party_participants, match_parties, match_requests, users restart identity cascade");
	}

	@Test
	void ACTIVE_현재_참가자는_동일_snapshot에서_채팅_handoff_상태를_반환한다() {
		long userId = insertUser();
		long partyId = insertActiveParty();
		insertParticipant(partyId, userId);

		CurrentMatchStateResponse response = currentStateQueryCoordinator.read(userId);

		assertEquals(MatchCurrentState.ACTIVE, response.state());
		assertEquals(partyId, response.chat().partyId());
	}

	@Test
	void scheduler가_실행되지_않아도_종료_한시간_전_ACTIVE_Party는_안내를_한번만_저장하고_ACTIVE_handoff를_반환한다() {
		long userId = insertUser();
		long partyId = insertActiveParty(Instant.now().minusSeconds(82_800));
		insertParticipant(partyId, userId);
		insertMatchChatRoom(partyId);

		CurrentMatchStateResponse first = currentStateQueryCoordinator.read(userId);
		CurrentMatchStateResponse second = currentStateQueryCoordinator.read(userId);

		assertEquals(MatchCurrentState.ACTIVE, first.state());
		assertEquals(partyId, first.chat().partyId());
		assertEquals(MatchCurrentState.ACTIVE, second.state());
		assertEquals(1, jdbcTemplate.queryForObject("""
			select count(*) from match_chat_messages message
			join match_chat_rooms room on room.id = message.match_chat_room_id
			where room.party_id = ? and message.system_event_key = 'CLOSES_IN_ONE_HOUR'
			""", Integer.class, partyId));
	}

	@Test
	void scheduler가_실행되지_않아도_기한을_넘긴_PREPARING_Party를_정리한_뒤_안정된_빈_상태를_반환한다() {
		long userId = insertUser();
		long partyId = insertPreparingParty(Instant.now().minusSeconds(301));
		insertParticipant(partyId, userId);

		CurrentMatchStateResponse response = currentStateQueryCoordinator.read(userId);

		assertEquals(null, response.state());
		assertEquals(0,
			jdbcTemplate.queryForObject("select count(*) from match_parties where id = ?", Integer.class, partyId));
	}

	@Test
	void scheduler가_실행되지_않아도_기한을_넘긴_OPEN_Proposal을_종결한_뒤_PAUSED를_반환한다() {
		long userId = insertUser();
		long requestId = insertProposedRequest(userId);
		long proposalId = insertDueOpenProposal();
		insertProposalMember(proposalId, requestId, userId);

		CurrentMatchStateResponse response = currentStateQueryCoordinator.read(userId);

		assertEquals(MatchCurrentState.PAUSED, response.state());
		assertEquals("EXPIRED", jdbcTemplate.queryForObject(
			"select status from match_proposals where id = ?", String.class, proposalId));
	}

	@Test
	void scheduler가_실행되지_않아도_기한을_넘긴_ACTIVE_Party를_종료한_뒤_빈_상태를_반환한다() {
		long userId = insertUser();
		long partyId = insertExpiredActiveParty();
		insertParticipant(partyId, userId);

		CurrentMatchStateResponse response = currentStateQueryCoordinator.read(userId);

		assertEquals(null, response.state());
		assertEquals("CLOSED", jdbcTemplate.queryForObject(
			"select status from match_parties where id = ?", String.class, partyId));
	}

	@Test
	void ReadService는_snapshot_operationTime에_이미_지난_OPEN_Proposal을_응답으로_조합하지_않는다() {
		long userId = insertUser();
		long requestId = insertProposedRequest(userId);
		long proposalId = insertOpenProposal(EXPIRED_SNAPSHOT_FIXTURE_TIME);
		insertProposalMember(proposalId, requestId, userId);

		assertCurrentStateIsNotStable(() -> currentStateReadService.read(userId));
	}

	@Test
	void ReadService는_snapshot_operationTime에_5분을_지난_PREPARING_Party를_응답으로_조합하지_않는다() {
		long userId = insertUser();
		long partyId = insertPreparingParty(EXPIRED_SNAPSHOT_FIXTURE_TIME);
		insertParticipant(partyId, userId);

		assertCurrentStateIsNotStable(() -> currentStateReadService.read(userId));
	}

	@Test
	void ReadService는_snapshot_operationTime에_종료_시각을_지난_ACTIVE_Party를_응답으로_조합하지_않는다() {
		long userId = insertUser();
		long partyId = insertActiveParty(EXPIRED_SNAPSHOT_FIXTURE_TIME);
		insertParticipant(partyId, userId);

		assertCurrentStateIsNotStable(() -> currentStateReadService.read(userId));
	}

	@Test
	void ReadService는_snapshot_operationTime에_종료_한시간_이내_안내가_없는_ACTIVE_Party를_응답으로_조합하지_않는다() {
		long userId = insertUser();
		long partyId = insertActiveParty(Instant.now().minusSeconds(82_800));
		insertParticipant(partyId, userId);
		insertMatchChatRoom(partyId);

		assertCurrentStateIsNotStable(() -> currentStateReadService.read(userId));
	}

	@Test
	void 요청과_ACTIVE_Party를_같이_관찰하면_우선순위를_정하지_않고_불안정_오류로_끝난다() {
		long userId = insertUser();
		insertWaitingRequest(userId);
		long partyId = insertActiveParty();
		insertParticipant(partyId, userId);

		BusinessException exception = assertThrows(BusinessException.class,
			() -> currentStateQueryCoordinator.read(userId));

		assertEquals(ErrorCode.MATCH_CURRENT_STATE_NOT_STABLE, exception.getErrorCode());
	}

	@Test
	void 영구_요청_Party_불안정은_ReadService를_정확히_세번_호출한_뒤_오류로_끝난다() {
		long userId = insertUser();
		insertWaitingRequest(userId);
		long partyId = insertActiveParty();
		insertParticipant(partyId, userId);
		clearInvocations(currentStateReadService);

		BusinessException exception = assertThrows(BusinessException.class,
			() -> currentStateQueryCoordinator.read(userId));

		assertEquals(ErrorCode.MATCH_CURRENT_STATE_NOT_STABLE, exception.getErrorCode());
		verify(currentStateReadService, times(3)).read(userId);
	}

	@Test
	void 새_snapshot은_PostgreSQL_repeatable_read와_transaction_timestamp_operationTime을_사용한다() {
		long userId = insertUser();
		AtomicReference<String> isolation = new AtomicReference<>();
		AtomicReference<Timestamp> operationTimestamp = new AtomicReference<>();
		doAnswer(invocation -> {
			Timestamp timestamp = (Timestamp)invocation.callRealMethod();
			operationTimestamp.set(timestamp);
			isolation.set(jdbcTemplate.queryForObject("show transaction_isolation", String.class));
			return timestamp;
		}).when(jdbcTemplate).queryForObject(eq("select current_timestamp"), eq(Timestamp.class));

		CurrentMatchStateResponse response = currentStateQueryCoordinator.read(userId);

		assertEquals("repeatable read", isolation.get());
		assertEquals(operationTimestamp.get().toInstant(), response.operationTime());
	}

	private long insertUser() {
		Instant now = Instant.now();
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', 'participant', ?, ?) returning id",
			Long.class, "current-state-" + UUID.randomUUID() + "@example.com", Timestamp.from(now),
			Timestamp.from(now));
	}

	private long insertActiveParty() {
		return insertActiveParty(Instant.now().minusSeconds(600));
	}

	private long insertActiveParty(Instant openedAt) {
		return jdbcTemplate.queryForObject(
			"insert into match_parties (status, preparing_started_at, chat_opened_at, closes_at, created_at, updated_at) values ('ACTIVE', ?, ?, ?, ?, ?) returning id",
			Long.class, Timestamp.from(openedAt), Timestamp.from(openedAt),
			Timestamp.from(openedAt.plusSeconds(86_400)),
			Timestamp.from(openedAt), Timestamp.from(openedAt));
	}

	private long insertPreparingParty(Instant preparingStartedAt) {
		return jdbcTemplate.queryForObject(
			"insert into match_parties (status, preparing_started_at, created_at, updated_at) values ('PREPARING', ?, ?, ?) returning id",
			Long.class, Timestamp.from(preparingStartedAt), Timestamp.from(preparingStartedAt),
			Timestamp.from(preparingStartedAt));
	}

	private long insertExpiredActiveParty() {
		Instant closesAt = Instant.now().minusSeconds(1);
		return jdbcTemplate.queryForObject(
			"insert into match_parties (status, preparing_started_at, chat_opened_at, closes_at, created_at, updated_at) values ('ACTIVE', ?, ?, ?, ?, ?) returning id",
			Long.class, Timestamp.from(closesAt.minusSeconds(86_400)), Timestamp.from(closesAt.minusSeconds(86_400)),
			Timestamp.from(closesAt), Timestamp.from(closesAt), Timestamp.from(closesAt));
	}

	private void insertParticipant(long partyId, long userId) {
		jdbcTemplate.update(
			"insert into match_party_participants (party_id, user_id, participant_ref, created_at) values (?, ?, ?, ?)",
			partyId, userId, UUID.randomUUID(), Timestamp.from(Instant.now()));
	}

	private void insertMatchChatRoom(long partyId) {
		Instant now = Instant.now();
		jdbcTemplate.update("insert into match_chat_rooms (party_id, created_at, updated_at) values (?, ?, ?)",
			partyId, Timestamp.from(now), Timestamp.from(now));
	}

	private long insertProposedRequest(long userId) {
		Instant now = Instant.now();
		return jdbcTemplate.queryForObject(
			"insert into match_requests (user_id, min_party_size, max_party_size, status, queued_at, priority_since, proposed_at, created_at, updated_at) values (?, 1, 1, 'PROPOSED', ?, ?, ?, ?, ?) returning id",
			Long.class, userId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
			Timestamp.from(now));
	}

	private void insertWaitingRequest(long userId) {
		Instant now = Instant.now();
		jdbcTemplate.update(
			"insert into match_requests (user_id, min_party_size, max_party_size, status, queued_at, priority_since, created_at, updated_at) values (?, 1, 1, 'WAITING', ?, ?, ?, ?)",
			userId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
	}

	private long insertDueOpenProposal() {
		Instant now = Instant.now().minusSeconds(31);
		return insertOpenProposal(now);
	}

	private long insertOpenProposal(Instant respondBy) {
		return jdbcTemplate.queryForObject(
			"insert into match_proposals (party_size, status, respond_by, created_at, updated_at) values (1, 'OPEN', ?, ?, ?) returning id",
			Long.class, Timestamp.from(respondBy), Timestamp.from(respondBy), Timestamp.from(respondBy));
	}

	private void insertProposalMember(long proposalId, long requestId, long userId) {
		Instant now = Instant.now();
		jdbcTemplate.update(
			"insert into match_proposal_members (proposal_id, match_request_id, user_id, response_status, created_at, updated_at) values (?, ?, ?, 'PENDING', ?, ?)",
			proposalId, requestId, userId, Timestamp.from(now), Timestamp.from(now));
	}

	private void assertCurrentStateIsNotStable(ThrowingRunnable read) {
		BusinessException exception = assertThrows(BusinessException.class, read::run);

		assertEquals(ErrorCode.MATCH_CURRENT_STATE_NOT_STABLE, exception.getErrorCode());
	}

	@FunctionalInterface
	private interface ThrowingRunnable {

		void run();
	}
}
