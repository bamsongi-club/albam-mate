package cloud.bamsongi.albammate.chat.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.chat.match.adapter.MatchChatProvisionAdapter;
import cloud.bamsongi.albammate.chat.match.adapter.MatchChatSystemMessageAdapter;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatMessageRepository;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatRoomRepository;
import cloud.bamsongi.albammate.matching.contract.MatchChatCleanupPort;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

/**
 * CHAT-T1 — provision·system-message adapter를 실제 PostgreSQL 트랜잭션 경계에서 검증한다.
 *
 * <p>같은 Party와 lifecycle event로 서로 다른 호출자 트랜잭션에서 반복 호출해도 채팅방·시스템 메시지는 각각 한 건으로
 * 수렴하고, 호출자 트랜잭션이 rollback되면 chat 변경도 함께 사라진다.
 */
@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class MatchChatAdapterTransactionPostgresTest extends SharedPostgresIntegrationSupport {

	@Autowired
	private MatchChatProvisionAdapter matchChatProvisionAdapter;
	@Autowired
	private MatchChatSystemMessageAdapter matchChatSystemMessageAdapter;
	@Autowired
	private MatchChatCleanupPort matchChatCleanupPort;
	@Autowired
	private MatchChatRoomRepository matchChatRoomRepository;
	@Autowired
	private MatchChatMessageRepository matchChatMessageRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private PlatformTransactionManager transactionManager;

	@AfterEach
	void tearDown() {
		jdbcTemplate
			.execute("truncate table match_chat_messages, match_chat_rooms, match_parties restart identity cascade");
		jdbcTemplate.execute("truncate table chat_messages, chat_rooms, rooms, users restart identity cascade");
	}

	@Test
	void 서로_다른_호출자_트랜잭션에서_반복_provision해도_채팅방은_한_건으로_수렴한다() {
		long partyId = insertPreparingParty();
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

		transactionTemplate.executeWithoutResult(status -> matchChatProvisionAdapter.provision(partyId));
		transactionTemplate.executeWithoutResult(status -> matchChatProvisionAdapter.provision(partyId));

		assertEquals(1, matchChatRoomRepository.count());
		assertTrue(matchChatRoomRepository.findByPartyId(partyId).isPresent());
	}

	@Test
	void provision_호출자_트랜잭션이_롤백되면_채팅방도_생성되지_않는다() {
		long partyId = insertPreparingParty();
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

		assertThrows(
			IllegalStateException.class,
			() -> transactionTemplate.executeWithoutResult(status -> {
				matchChatProvisionAdapter.provision(partyId);
				throw new IllegalStateException("rollback check");
			}));

		assertTrue(matchChatRoomRepository.findByPartyId(partyId).isEmpty());
	}

	@Test
	void 서로_다른_호출자_트랜잭션에서_반복_기록해도_같은_lifecycle_시스템_메시지는_한_건으로_수렴한다() {
		long partyId = insertActivePartyWithChatRoom();
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

		transactionTemplate.executeWithoutResult(
			status -> matchChatSystemMessageAdapter.record(partyId, "CHAT_OPENED"));
		transactionTemplate.executeWithoutResult(
			status -> matchChatSystemMessageAdapter.record(partyId, "CHAT_OPENED"));

		assertEquals(1, matchChatMessageRepository.count());
	}

	@Test
	void 서로_다른_lifecycle_이벤트는_각각_저장된다() {
		long partyId = insertActivePartyWithChatRoom();
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

		transactionTemplate.executeWithoutResult(
			status -> matchChatSystemMessageAdapter.record(partyId, "CHAT_OPENED"));
		transactionTemplate.executeWithoutResult(
			status -> matchChatSystemMessageAdapter.record(partyId, "CLOSES_IN_ONE_HOUR"));

		assertEquals(2, matchChatMessageRepository.count());
	}

	@Test
	void system_message_호출자_트랜잭션이_롤백되면_시스템_메시지도_남지_않는다() {
		long partyId = insertActivePartyWithChatRoom();
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

		assertThrows(
			IllegalStateException.class,
			() -> transactionTemplate.executeWithoutResult(status -> {
				matchChatSystemMessageAdapter.record(partyId, "CHAT_OPENED");
				throw new IllegalStateException("rollback check");
			}));

		assertEquals(0, matchChatMessageRepository.count());
	}

	@Test
	void 정리는_호출자_트랜잭션에서_MATCH_채팅만_삭제하고_반복_롤백_무트랜잭션을_검증한다() {
		P1ChatFixture p1Chat = insertP1ChatRoomWithMessage();
		long partyId = insertPreparingParty();
		long matchChatRoomId = insertMatchChatRoom(partyId);
		insertMatchUserMessage(matchChatRoomId, p1Chat.userId());
		insertMatchSystemMessage(matchChatRoomId);
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

		transactionTemplate.executeWithoutResult(status -> matchChatCleanupPort.cleanup(partyId));

		assertEquals(0, countMatchChatRooms(partyId));
		assertEquals(0, countMatchChatMessages(matchChatRoomId));
		assertEquals(1, countP1ChatMessages(p1Chat.chatRoomId()));

		transactionTemplate.executeWithoutResult(status -> matchChatCleanupPort.cleanup(partyId));
		assertEquals(0, countMatchChatRooms(partyId));

		long rollbackPartyId = insertPreparingParty();
		long rollbackMatchChatRoomId = insertMatchChatRoom(rollbackPartyId);
		insertMatchUserMessage(rollbackMatchChatRoomId, p1Chat.userId());
		insertMatchSystemMessage(rollbackMatchChatRoomId);

		assertThrows(
			IllegalStateException.class,
			() -> transactionTemplate.executeWithoutResult(status -> {
				matchChatCleanupPort.cleanup(rollbackPartyId);
				throw new IllegalStateException("rollback check");
			}));

		assertEquals(1, countMatchChatRooms(rollbackPartyId));
		assertEquals(2, countMatchChatMessages(rollbackMatchChatRoomId));
		assertEquals(1, countP1ChatMessages(p1Chat.chatRoomId()));
		assertThrows(IllegalTransactionStateException.class, () -> matchChatCleanupPort.cleanup(partyId));
	}

	@Test
	void cleanup_반환_뒤_참가자와_파티를_삭제하면_모든_MATCH_데이터가_함께_커밋되거나_롤백된다() {
		long userId = insertUser("match-cleanup-transaction@example.com");
		long partyId = insertPreparingParty();
		long matchChatRoomId = insertMatchChatRoom(partyId);
		insertMatchParticipant(partyId, userId);
		insertMatchUserMessage(matchChatRoomId, userId);
		insertMatchSystemMessage(matchChatRoomId);
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

		transactionTemplate.executeWithoutResult(status -> {
			matchChatCleanupPort.cleanup(partyId);
			deletePartyParticipantsAndParty(partyId);
		});

		assertEquals(0, countMatchChatRooms(partyId));
		assertEquals(0, countMatchChatMessages(matchChatRoomId));
		assertEquals(0, countMatchPartyParticipants(partyId));
		assertEquals(0, countMatchParties(partyId));

		long rollbackPartyId = insertPreparingParty();
		long rollbackMatchChatRoomId = insertMatchChatRoom(rollbackPartyId);
		insertMatchParticipant(rollbackPartyId, userId);
		insertMatchUserMessage(rollbackMatchChatRoomId, userId);
		insertMatchSystemMessage(rollbackMatchChatRoomId);

		assertThrows(
			IllegalStateException.class,
			() -> transactionTemplate.executeWithoutResult(status -> {
				matchChatCleanupPort.cleanup(rollbackPartyId);
				deletePartyParticipantsAndParty(rollbackPartyId);
				throw new IllegalStateException("rollback check");
			}));

		assertEquals(1, countMatchChatRooms(rollbackPartyId));
		assertEquals(2, countMatchChatMessages(rollbackMatchChatRoomId));
		assertEquals(1, countMatchPartyParticipants(rollbackPartyId));
		assertEquals(1, countMatchParties(rollbackPartyId));
	}

	private long insertPreparingParty() {
		return jdbcTemplate.queryForObject(
			"insert into match_parties (status, preparing_started_at, created_at, updated_at) "
				+ "values ('PREPARING', current_timestamp, current_timestamp, current_timestamp) returning id",
			Long.class);
	}

	private long insertActivePartyWithChatRoom() {
		long partyId = jdbcTemplate.queryForObject(
			"insert into match_parties "
				+ "(status, preparing_started_at, chat_opened_at, closes_at, created_at, updated_at) "
				+ "values ('ACTIVE', current_timestamp, current_timestamp, "
				+ "current_timestamp + interval '1 day', current_timestamp, current_timestamp) returning id",
			Long.class);
		jdbcTemplate.update(
			"insert into match_chat_rooms (party_id, created_at, updated_at) "
				+ "values (?, current_timestamp, current_timestamp)",
			partyId);
		return partyId;
	}

	private long insertMatchChatRoom(long partyId) {
		return jdbcTemplate.queryForObject(
			"insert into match_chat_rooms (party_id, created_at, updated_at) "
				+ "values (?, current_timestamp, current_timestamp) returning id",
			Long.class,
			partyId);
	}

	private long insertUser(String email) {
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'hash', 'match-cleanup-transaction', current_timestamp, current_timestamp) returning id",
			Long.class,
			email);
	}

	private void insertMatchParticipant(long partyId, long userId) {
		jdbcTemplate.update(
			"insert into match_party_participants (party_id, user_id, participant_ref, created_at) "
				+ "values (?, ?, ?, current_timestamp)",
			partyId,
			userId,
			UUID.randomUUID());
	}

	private void insertMatchUserMessage(long matchChatRoomId, long userId) {
		jdbcTemplate.update(
			"insert into match_chat_messages "
				+ "(match_chat_room_id, sender_user_id, message_type, client_message_id, content, created_at) "
				+ "values (?, ?, 'USER', 'cleanup-user-message', 'MATCH user message', current_timestamp)",
			matchChatRoomId,
			userId);
	}

	private void insertMatchSystemMessage(long matchChatRoomId) {
		jdbcTemplate.update(
			"insert into match_chat_messages "
				+ "(match_chat_room_id, message_type, system_event_key, content, created_at) "
				+ "values (?, 'SYSTEM', 'CHAT_OPENED', 'MATCH system message', current_timestamp)",
			matchChatRoomId);
	}

	private P1ChatFixture insertP1ChatRoomWithMessage() {
		long userId = jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values ('match-cleanup@example.com', 'hash', 'match-cleanup', current_timestamp, current_timestamp) "
				+ "returning id",
			Long.class);
		long roomId = jdbcTemplate.queryForObject(
			"insert into rooms (host_user_id, room_type, title, experience_level, is_rulemaster_led, region, "
				+ "capacity, active_participant_count, start_at, place, status, created_at, updated_at) "
				+ "values (?, 'PERSON_FOCUSED', 'P1 room', 'ALL_LEVELS', false, '홍대', 1, 0, "
				+ "current_timestamp, '테스트', 'RECRUITING', current_timestamp, current_timestamp) returning id",
			Long.class,
			userId);
		long chatRoomId = jdbcTemplate.queryForObject(
			"insert into chat_rooms (room_id, created_at, updated_at) "
				+ "values (?, current_timestamp, current_timestamp) returning id",
			Long.class,
			roomId);
		jdbcTemplate.update(
			"insert into chat_messages (chat_room_id, sender_user_id, client_message_id, content, created_at) "
				+ "values (?, ?, 'p1-message', 'P1 room message', current_timestamp)",
			chatRoomId,
			userId);
		return new P1ChatFixture(userId, chatRoomId);
	}

	private int countMatchChatRooms(long partyId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from match_chat_rooms where party_id = ?", Integer.class, partyId);
	}

	private int countMatchChatMessages(long matchChatRoomId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from match_chat_messages where match_chat_room_id = ?", Integer.class, matchChatRoomId);
	}

	private void deletePartyParticipantsAndParty(long partyId) {
		jdbcTemplate.update("delete from match_party_participants where party_id = ?", partyId);
		jdbcTemplate.update("delete from match_parties where id = ?", partyId);
	}

	private int countMatchPartyParticipants(long partyId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from match_party_participants where party_id = ?", Integer.class, partyId);
	}

	private int countMatchParties(long partyId) {
		return jdbcTemplate.queryForObject("select count(*) from match_parties where id = ?", Integer.class, partyId);
	}

	private int countP1ChatMessages(long chatRoomId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from chat_messages where chat_room_id = ?", Integer.class, chatRoomId);
	}

	private record P1ChatFixture(long userId, long chatRoomId) {
	}
}
