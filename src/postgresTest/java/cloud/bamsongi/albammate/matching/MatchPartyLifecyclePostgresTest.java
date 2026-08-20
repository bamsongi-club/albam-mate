package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.matching.recovery.MatchClosedPartyCleanupExecutor;
import cloud.bamsongi.albammate.matching.recovery.MatchPartyLifecycleExecutor;
import cloud.bamsongi.albammate.matching.recovery.MatchPreparingRecoveryExecutor;
import cloud.bamsongi.albammate.matching.recovery.MatchRecoveryCoordinator;
import cloud.bamsongi.albammate.matching.repository.MatchPartyRepository;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class MatchPartyLifecyclePostgresTest extends SharedPostgresIntegrationSupport {

	@Autowired
	private MatchPartyLifecycleExecutor lifecycleExecutor;
	@Autowired
	private MatchPreparingRecoveryExecutor preparingRecoveryExecutor;
	@Autowired
	private MatchClosedPartyCleanupExecutor closedPartyCleanupExecutor;
	@Autowired
	private MatchRecoveryCoordinator recoveryCoordinator;
	@Autowired
	private MatchPartyRepository partyRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private DataSource dataSource;

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute(
			"truncate table match_chat_messages, match_chat_rooms, match_idempotency_records, match_proposal_members, match_proposals, match_party_participants, match_parties, match_requests, users restart identity cascade");
	}

	@Test
	void 기한_전_PREPARING_Party는_채팅을_준비하고_ACTIVE와_한번의_시작_안내로_전이한다() {
		long userId = insertUser();
		long partyId = insertPreparingParty(Instant.now().minusSeconds(60));
		insertParticipant(partyId, userId);

		preparingRecoveryExecutor.recover(partyId);

		assertEquals("ACTIVE", jdbcTemplate.queryForObject(
			"select status from match_parties where id = ?", String.class, partyId));
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from match_chat_rooms where party_id = ?", Integer.class, partyId));
		assertEquals(1, jdbcTemplate.queryForObject("""
			select count(*) from match_chat_messages message
			join match_chat_rooms room on room.id = message.match_chat_room_id
			where room.party_id = ? and message.system_event_key = 'CHAT_OPENED'
			""", Integer.class, partyId));
	}

	@Test
	void 종료_한시간_전_ACTIVE_Party는_종료_안내를_재시도에도_한번만_저장한다() {
		long partyId = insertActiveParty(Instant.now().minusSeconds(82_800));
		insertMatchChatRoom(partyId);

		lifecycleExecutor.recover(partyId);
		lifecycleExecutor.recover(partyId);

		assertEquals(1, jdbcTemplate.queryForObject("""
			select count(*) from match_chat_messages message
			join match_chat_rooms room on room.id = message.match_chat_room_id
			where room.party_id = ? and message.system_event_key = 'CLOSES_IN_ONE_HOUR'
			""", Integer.class, partyId));
	}

	@Test
	void 종료_시각을_넘긴_ACTIVE_Party는_CLOSED로_전이하고_종료_안내를_새로_만들지_않는다() {
		long partyId = insertActiveParty(Instant.now().minusSeconds(86_401));
		insertMatchChatRoom(partyId);

		lifecycleExecutor.recover(partyId);
		lifecycleExecutor.recover(partyId);

		assertEquals("CLOSED", jdbcTemplate.queryForObject(
			"select status from match_parties where id = ?", String.class, partyId));
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from match_parties where id = ? and closed_at is not null and purge_after is not null",
			Integer.class, partyId));
		assertEquals(0, jdbcTemplate.queryForObject("""
			select count(*) from match_chat_messages message
			join match_chat_rooms room on room.id = message.match_chat_room_id
			where room.party_id = ? and message.system_event_key = 'CLOSES_IN_ONE_HOUR'
			""", Integer.class, partyId));
	}

	@Test
	void Party_잠금_대기중_종료_시각을_넘기면_CLOSED가_종료_안내보다_우선한다() throws Exception {
		long partyId = insertActiveParty(Instant.now().minusSeconds(86_399));
		insertMatchChatRoom(partyId);

		try (var partyLock = dataSource.getConnection()) {
			partyLock.setAutoCommit(false);
			try (var statement = partyLock.prepareStatement("select id from match_parties where id = ? for update")) {
				statement.setLong(1, partyId);
				statement.executeQuery();
			}
			var pool = Executors.newSingleThreadExecutor();
			try {
				Future<?> recovery = pool.submit(() -> lifecycleExecutor.recover(partyId));
				awaitTransactionLockWait();
				TimeUnit.MILLISECONDS.sleep(1_200);
				partyLock.rollback();
				recovery.get(10, TimeUnit.SECONDS);
			} finally {
				pool.shutdownNow();
			}
		}

		assertEquals("CLOSED", jdbcTemplate.queryForObject(
			"select status from match_parties where id = ?", String.class, partyId));
		assertEquals(0, jdbcTemplate.queryForObject("""
			select count(*) from match_chat_messages message
			join match_chat_rooms room on room.id = message.match_chat_room_id
			where room.party_id = ? and message.system_event_key = 'CLOSES_IN_ONE_HOUR'
			""", Integer.class, partyId));
	}

	@Test
	void 보존_기한을_넘긴_CLOSED_Party는_채팅을_먼저_정리한_뒤_물리_삭제한다() {
		long partyId = insertClosedParty(Instant.now().minusSeconds(604_801));
		insertMatchChatRoom(partyId);

		closedPartyCleanupExecutor.cleanUp(partyId);

		assertEquals(0,
			jdbcTemplate.queryForObject("select count(*) from match_parties where id = ?", Integer.class, partyId));
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from match_chat_rooms where party_id = ?",
			Integer.class, partyId));
	}

	@Test
	void 준비_기한을_넘긴_Party는_채팅부터_정리하고_참가자와_Party를_삭제한_뒤_요청을_기존_시각의_WAITING으로_되돌린다() {
		long userId = insertUser();
		long requestId = insertMatchedRequest(userId);
		long proposalId = insertConfirmedProposal();
		insertProposalMember(proposalId, requestId, userId);
		long partyId = insertPreparingPartyForProposal(proposalId, Instant.now().minusSeconds(301));
		insertParticipant(partyId, userId);

		preparingRecoveryExecutor.recover(partyId);

		assertEquals(0,
			jdbcTemplate.queryForObject("select count(*) from match_parties where id = ?", Integer.class, partyId));
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from match_party_participants where party_id = ?",
			Integer.class, partyId));
		assertEquals("WAITING",
			jdbcTemplate.queryForObject("select status from match_requests where id = ?", String.class, requestId));
	}

	@Test
	void 채팅_준비_중_준비_기한을_넘긴_Party는_ACTIVE로_전이하지_않고_채팅과_Party를_정리한_뒤_요청_우선순위를_보존한다() {
		long userId = insertUser();
		long requestId = insertMatchedRequest(userId);
		Instant originalPrioritySince = jdbcTemplate.queryForObject(
			"select priority_since from match_requests where id = ?", Timestamp.class, requestId).toInstant();
		long proposalId = insertConfirmedProposal();
		insertProposalMember(proposalId, requestId, userId);
		long partyId = insertPreparingPartyForProposal(proposalId, Instant.now().minusSeconds(299));
		insertParticipant(partyId, userId);
		createChatProvisionDelayTrigger();
		try {
			preparingRecoveryExecutor.recover(partyId);

			assertEquals(0,
				jdbcTemplate.queryForObject("select count(*) from match_parties where id = ?", Integer.class, partyId));
			assertEquals(0, jdbcTemplate.queryForObject(
				"select count(*) from match_party_participants where party_id = ?", Integer.class, partyId));
			assertEquals(0, jdbcTemplate.queryForObject("select count(*) from match_chat_rooms where party_id = ?",
				Integer.class, partyId));
			assertEquals("WAITING",
				jdbcTemplate.queryForObject("select status from match_requests where id = ?", String.class, requestId));
			assertEquals(originalPrioritySince, jdbcTemplate.queryForObject(
				"select priority_since from match_requests where id = ?", Timestamp.class, requestId).toInstant());
		} finally {
			dropChatProvisionDelayTrigger();
		}
	}

	@Test
	void scheduler_coordinator는_기한을_넘긴_PREPARING_Party를_선별해_복구한다() {
		long userId = insertUser();
		long partyId = insertPreparingParty(Instant.now().minusSeconds(301));
		insertParticipant(partyId, userId);

		recoveryCoordinator.recoverDueParties();

		assertEquals(0,
			jdbcTemplate.queryForObject("select count(*) from match_parties where id = ?", Integer.class, partyId));
	}

	@Test
	void scheduler_coordinator는_기한을_넘긴_OPEN_Proposal을_EXPIRED와_PAUSED로_종결한다() {
		long userId = insertUser();
		long requestId = insertProposedRequest(userId);
		long proposalId = insertDueOpenProposal();
		insertPendingProposalMember(proposalId, requestId, userId);

		recoveryCoordinator.recoverDueParties();

		assertEquals("EXPIRED", jdbcTemplate.queryForObject(
			"select status from match_proposals where id = ?", String.class, proposalId));
		assertEquals("PAUSED", jdbcTemplate.queryForObject(
			"select status from match_requests where id = ?", String.class, requestId));
	}

	@Test
	void scheduler_coordinator는_종료_시각을_넘긴_ACTIVE_Party를_CLOSED로_전이한다() {
		long partyId = insertActiveParty(Instant.now().minusSeconds(86_401));

		recoveryCoordinator.recoverDueParties();

		assertEquals("CLOSED", jdbcTemplate.queryForObject(
			"select status from match_parties where id = ?", String.class, partyId));
	}

	@Test
	void 이미_종료_안내가_있는_낮은_ID_Party_뒤의_due_Party도_한번의_제한된_스캔에서_복구한다() {
		for (int index = 0; index < 201; index++) {
			long noticedPartyId = insertActiveParty(Instant.now().minusSeconds(82_800));
			long chatRoomId = insertMatchChatRoom(noticedPartyId);
			insertCloseNotice(chatRoomId);
		}
		long expiredPartyId = insertActiveParty(Instant.now().minusSeconds(86_401));
		insertMatchChatRoom(expiredPartyId);

		recoveryCoordinator.recoverDueParties();

		assertEquals("CLOSED", jdbcTemplate.queryForObject(
			"select status from match_parties where id = ?", String.class, expiredPartyId));
	}

	@Test
	void scheduler_coordinator는_보존_기한을_넘긴_CLOSED_Party의_채팅을_먼저_정리하고_삭제한다() {
		long partyId = insertClosedParty(Instant.now().minusSeconds(604_801));
		insertMatchChatRoom(partyId);

		recoveryCoordinator.recoverDueParties();

		assertEquals(0,
			jdbcTemplate.queryForObject("select count(*) from match_parties where id = ?", Integer.class, partyId));
	}

	@Test
	void lifecycle_후보_조회는_아직_시작하지_않은_PREPARING과_아직_기한이_아닌_ACTIVE_CLOSED를_제외한다() {
		Instant futureTime = Instant.now().plusSeconds(60);
		insertPreparingParty(futureTime);
		insertActiveParty(futureTime);
		insertClosedParty(futureTime);

		List<Long> candidateIds = partyRepository.findLifecycleCandidateIdsAfter(0, 100);

		assertEquals(List.of(), candidateIds);
	}

	@Test
	void lifecycle_후보_조회는_기한을_넘긴_CLOSED_Party를_한_번에_100건만_반환한다() {
		for (int index = 0; index < 101; index++) {
			insertClosedParty(Instant.now().minusSeconds(604_801));
		}

		List<Long> candidateIds = partyRepository.findLifecycleCandidateIdsAfter(0, 100);

		assertEquals(100, candidateIds.size());
	}

	@Test
	void coordinator_retention은_due_원자료를_100건씩_삭제하고_아직_보존중인_행은_남긴다() {
		long userId = insertUser();
		Instant dueTime = Instant.now().minusSeconds(1);
		Instant retainedUntil = Instant.now().plusSeconds(604_800);
		long dueRequestId = insertTerminalRequest(userId, "CANCELED", dueTime);
		long retainedRequestId = insertTerminalRequest(userId, "MATCHED", retainedUntil);
		long dueProposalId = insertTerminalProposal("CANCELED", dueTime);
		long retainedProposalId = insertTerminalProposal("EXPIRED", retainedUntil);
		for (int index = 0; index < 101; index++) {
			insertIdempotencyRecord(userId, "due-record-" + index, dueTime);
		}
		insertIdempotencyRecord(userId, "retained-record", retainedUntil);

		recoveryCoordinator.recoverDueParties();

		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from match_requests where id = ?", Integer.class,
			dueRequestId));
		assertEquals(1, jdbcTemplate.queryForObject("select count(*) from match_requests where id = ?", Integer.class,
			retainedRequestId));
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from match_proposals where id = ?", Integer.class,
			dueProposalId));
		assertEquals(1, jdbcTemplate.queryForObject("select count(*) from match_proposals where id = ?", Integer.class,
			retainedProposalId));
		assertEquals(2, jdbcTemplate.queryForObject("select count(*) from match_idempotency_records", Integer.class));
	}

	@Test
	void 준비_기한_초과_cleanup_port가_실패하면_Party_참가자_요청_변경을_함께_롤백한다() {
		long userId = insertUser();
		long requestId = insertMatchedRequest(userId);
		long proposalId = insertConfirmedProposal();
		insertProposalMember(proposalId, requestId, userId);
		long partyId = insertPreparingPartyForProposal(proposalId, Instant.now().minusSeconds(301));
		insertParticipant(partyId, userId);
		insertMatchChatRoom(partyId);
		createCleanupFailureTrigger();
		try {
			assertThrows(RuntimeException.class, () -> preparingRecoveryExecutor.recover(partyId));

			assertEquals(1,
				jdbcTemplate.queryForObject("select count(*) from match_parties where id = ?", Integer.class, partyId));
			assertEquals(1, jdbcTemplate.queryForObject(
				"select count(*) from match_party_participants where party_id = ?", Integer.class, partyId));
			assertEquals("MATCHED",
				jdbcTemplate.queryForObject("select status from match_requests where id = ?", String.class, requestId));
			assertEquals(1, jdbcTemplate.queryForObject("select count(*) from match_chat_rooms where party_id = ?",
				Integer.class, partyId));
		} finally {
			dropCleanupFailureTrigger();
		}
	}

	private long insertUser() {
		Instant now = Instant.now();
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?) returning id",
			Long.class, "party-lifecycle-" + UUID.randomUUID() + "@example.com", "participant",
			Timestamp.from(now), Timestamp.from(now));
	}

	private long insertPreparingParty(Instant preparingStartedAt) {
		return jdbcTemplate.queryForObject(
			"insert into match_parties (status, preparing_started_at, created_at, updated_at) values ('PREPARING', ?, ?, ?) returning id",
			Long.class, Timestamp.from(preparingStartedAt), Timestamp.from(preparingStartedAt),
			Timestamp.from(preparingStartedAt));
	}

	private long insertProposedRequest(long userId) {
		Instant now = Instant.now();
		return jdbcTemplate.queryForObject(
			"insert into match_requests (user_id, min_party_size, max_party_size, status, queued_at, priority_since, proposed_at, created_at, updated_at) values (?, 1, 1, 'PROPOSED', ?, ?, ?, ?, ?) returning id",
			Long.class, userId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
			Timestamp.from(now));
	}

	private long insertDueOpenProposal() {
		Instant now = Instant.now().minusSeconds(31);
		return jdbcTemplate.queryForObject(
			"insert into match_proposals (party_size, status, respond_by, created_at, updated_at) values (1, 'OPEN', ?, ?, ?) returning id",
			Long.class, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
	}

	private void insertPendingProposalMember(long proposalId, long requestId, long userId) {
		Instant now = Instant.now();
		jdbcTemplate.update(
			"insert into match_proposal_members (proposal_id, match_request_id, user_id, response_status, created_at, updated_at) values (?, ?, ?, 'PENDING', ?, ?)",
			proposalId, requestId, userId, Timestamp.from(now), Timestamp.from(now));
	}

	private long insertActiveParty(Instant chatOpenedAt) {
		Instant closesAt = chatOpenedAt.plusSeconds(86_400);
		return jdbcTemplate.queryForObject(
			"insert into match_parties (status, preparing_started_at, chat_opened_at, closes_at, created_at, updated_at) values ('ACTIVE', ?, ?, ?, ?, ?) returning id",
			Long.class, Timestamp.from(chatOpenedAt), Timestamp.from(chatOpenedAt), Timestamp.from(closesAt),
			Timestamp.from(chatOpenedAt), Timestamp.from(chatOpenedAt));
	}

	private long insertClosedParty(Instant closedAt) {
		return jdbcTemplate.queryForObject(
			"insert into match_parties (status, preparing_started_at, chat_opened_at, closes_at, closed_at, purge_after, created_at, updated_at) values ('CLOSED', ?, ?, ?, ?, ?, ?, ?) returning id",
			Long.class, Timestamp.from(closedAt.minusSeconds(86_400)), Timestamp.from(closedAt.minusSeconds(86_400)),
			Timestamp.from(closedAt), Timestamp.from(closedAt), Timestamp.from(closedAt.plusSeconds(604_800)),
			Timestamp.from(closedAt), Timestamp.from(closedAt));
	}

	private long insertPreparingPartyForProposal(long proposalId, Instant preparingStartedAt) {
		return jdbcTemplate.queryForObject(
			"insert into match_parties (proposal_id, status, preparing_started_at, created_at, updated_at) values (?, 'PREPARING', ?, ?, ?) returning id",
			Long.class, proposalId, Timestamp.from(preparingStartedAt), Timestamp.from(preparingStartedAt),
			Timestamp.from(preparingStartedAt));
	}

	private long insertMatchedRequest(long userId) {
		Instant now = Instant.now().minusSeconds(600);
		return jdbcTemplate.queryForObject(
			"insert into match_requests (user_id, min_party_size, max_party_size, status, queued_at, priority_since, matched_at, created_at, updated_at) values (?, 1, 1, 'MATCHED', ?, ?, ?, ?, ?) returning id",
			Long.class, userId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
			Timestamp.from(now));
	}

	private long insertConfirmedProposal() {
		Instant now = Instant.now();
		return jdbcTemplate.queryForObject(
			"insert into match_proposals (party_size, status, respond_by, confirmed_at, created_at, updated_at) values (1, 'CONFIRMED', ?, ?, ?, ?) returning id",
			Long.class, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
	}

	private void insertProposalMember(long proposalId, long requestId, long userId) {
		Instant now = Instant.now();
		jdbcTemplate.update(
			"insert into match_proposal_members (proposal_id, match_request_id, user_id, response_status, responded_at, created_at, updated_at) values (?, ?, ?, 'ACCEPTED', ?, ?, ?)",
			proposalId, requestId, userId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
	}

	private long insertTerminalRequest(long userId, String status, Instant purgeAfter) {
		Instant now = Instant.now();
		return jdbcTemplate.queryForObject(
			"insert into match_requests (user_id, min_party_size, max_party_size, status, queued_at, priority_since, purge_after, created_at, updated_at) values (?, 1, 1, ?, ?, ?, ?, ?, ?) returning id",
			Long.class, userId, status, Timestamp.from(now), Timestamp.from(now), Timestamp.from(purgeAfter),
			Timestamp.from(now), Timestamp.from(now));
	}

	private long insertTerminalProposal(String status, Instant purgeAfter) {
		Instant now = Instant.now();
		return jdbcTemplate.queryForObject(
			"insert into match_proposals (party_size, status, respond_by, purge_after, created_at, updated_at) values (1, ?, ?, ?, ?, ?) returning id",
			Long.class, status, Timestamp.from(now), Timestamp.from(purgeAfter), Timestamp.from(now),
			Timestamp.from(now));
	}

	private void insertIdempotencyRecord(long userId, String key, Instant expiresAt) {
		Instant now = Instant.now();
		jdbcTemplate.update(
			"insert into match_idempotency_records (user_id, idempotency_key, operation, payload_fingerprint, created_at, expires_at) values (?, ?, 'MATCH_REQUEST_CREATE', 'fixture', ?, ?)",
			userId, key, Timestamp.from(now), Timestamp.from(expiresAt));
	}

	private void insertParticipant(long partyId, long userId) {
		jdbcTemplate.update(
			"insert into match_party_participants (party_id, user_id, participant_ref, created_at) values (?, ?, ?, ?)",
			partyId, userId, UUID.randomUUID(), Timestamp.from(Instant.now()));
	}

	private long insertMatchChatRoom(long partyId) {
		Instant now = Instant.now();
		return jdbcTemplate.queryForObject(
			"insert into match_chat_rooms (party_id, created_at, updated_at) values (?, ?, ?) returning id",
			Long.class, partyId, Timestamp.from(now), Timestamp.from(now));
	}

	private void insertCloseNotice(long chatRoomId) {
		Instant now = Instant.now();
		jdbcTemplate.update("""
			insert into match_chat_messages
			(match_chat_room_id, message_type, system_event_key, content, created_at)
			values (?, 'SYSTEM', 'CLOSES_IN_ONE_HOUR', '채팅이 1시간 이내에 종료됩니다.', ?)
			""", chatRoomId, Timestamp.from(now));
	}

	private void awaitTransactionLockWait() throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			Boolean waiting = jdbcTemplate.queryForObject(
				"select exists (select 1 from pg_locks where locktype = 'transactionid' and not granted)",
				Boolean.class);
			if (Boolean.TRUE.equals(waiting)) {
				return;
			}
			TimeUnit.MILLISECONDS.sleep(25);
		}
		throw new AssertionError("Party 잠금 대기 상태를 관찰하지 못했습니다.");
	}

	private void createCleanupFailureTrigger() {
		jdbcTemplate.execute("""
			create function fail_match_chat_cleanup() returns trigger language plpgsql as $$
			begin
				raise exception 'forced cleanup failure';
			end;
			$$
			""");
		jdbcTemplate.execute(
			"create trigger fail_match_chat_cleanup before delete on match_chat_rooms for each row execute function fail_match_chat_cleanup()");
	}

	private void dropCleanupFailureTrigger() {
		jdbcTemplate.execute("drop trigger if exists fail_match_chat_cleanup on match_chat_rooms");
		jdbcTemplate.execute("drop function if exists fail_match_chat_cleanup()");
	}

	private void createChatProvisionDelayTrigger() {
		jdbcTemplate.execute("""
			create function delay_match_chat_provision() returns trigger language plpgsql as $$
			begin
				perform pg_sleep(2);
				return new;
			end;
			$$
			""");
		jdbcTemplate.execute(
			"create trigger delay_match_chat_provision before insert on match_chat_rooms for each row execute function delay_match_chat_provision()");
	}

	private void dropChatProvisionDelayTrigger() {
		jdbcTemplate.execute("drop trigger if exists delay_match_chat_provision on match_chat_rooms");
		jdbcTemplate.execute("drop function if exists delay_match_chat_provision()");
	}
}
