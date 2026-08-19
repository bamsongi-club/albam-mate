package cloud.bamsongi.albammate.chat.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.chat.match.adapter.MatchChatProvisionAdapter;
import cloud.bamsongi.albammate.chat.match.adapter.MatchChatSystemMessageAdapter;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatMessageRepository;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatRoomRepository;

/**
 * CHAT-T1 — provision·system-message adapter를 실제 PostgreSQL 트랜잭션 경계에서 검증한다.
 *
 * <p>같은 Party와 lifecycle event로 서로 다른 호출자 트랜잭션에서 반복 호출해도 채팅방·시스템 메시지는 각각 한 건으로
 * 수렴하고, 호출자 트랜잭션이 rollback되면 chat 변경도 함께 사라진다.
 */
@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class MatchChatAdapterTransactionPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_match_chat_adapter_transaction_test");

	@Autowired
	private MatchChatProvisionAdapter matchChatProvisionAdapter;
	@Autowired
	private MatchChatSystemMessageAdapter matchChatSystemMessageAdapter;
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
}
