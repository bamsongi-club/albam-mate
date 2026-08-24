package cloud.bamsongi.albammate.chat.match.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.bamsongi.albammate.chat.match.repository.MatchChatRoomRepository;

/** CHAT-T1: provision adapter의 멱등 저장과 호출자 트랜잭션 rollback 전파를 검증한다. */
@SpringBootTest
class MatchChatProvisionAdapterTest {

	private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");

	@Autowired
	private MatchChatProvisionAdapter matchChatProvisionAdapter;
	@Autowired
	private MatchChatRoomRepository matchChatRoomRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private final List<Long> partyIds = new ArrayList<>();

	@AfterEach
	void tearDown() {
		partyIds.forEach(partyId -> jdbcTemplate.update("delete from match_chat_rooms where party_id = ?", partyId));
		partyIds.forEach(partyId -> jdbcTemplate.update("delete from match_parties where id = ?", partyId));
	}

	@Test
	void 같은_Party로_서로_다른_호출자_트랜잭션에서_반복_호출해도_채팅방은_한_건으로_수렴한다() {
		long partyId = insertPreparingParty();
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

		transactionTemplate.executeWithoutResult(status -> matchChatProvisionAdapter.provision(partyId));
		transactionTemplate.executeWithoutResult(status -> matchChatProvisionAdapter.provision(partyId));

		assertEquals(1, matchChatRoomRepository.count());
		assertTrue(matchChatRoomRepository.findByPartyId(partyId).isPresent());
	}

	@Test
	void 호출자_트랜잭션이_롤백되면_채팅방도_생성되지_않는다() {
		long partyId = insertPreparingParty();
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

		transactionTemplate.executeWithoutResult(status -> {
			matchChatProvisionAdapter.provision(partyId);
			status.setRollbackOnly();
		});

		assertTrue(matchChatRoomRepository.findByPartyId(partyId).isEmpty());
	}

	private long insertPreparingParty() {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(
				"insert into match_parties (status, preparing_started_at, created_at, updated_at) "
					+ "values ('PREPARING', ?, ?, ?)",
				Statement.RETURN_GENERATED_KEYS);
			statement.setTimestamp(1, Timestamp.from(NOW));
			statement.setTimestamp(2, Timestamp.from(NOW));
			statement.setTimestamp(3, Timestamp.from(NOW));
			return statement;
		}, keyHolder);
		long partyId = Objects.requireNonNull(keyHolder.getKey()).longValue();
		partyIds.add(partyId);
		return partyId;
	}
}
