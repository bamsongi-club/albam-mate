package cloud.bamsongi.albammate.chat.match.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

import cloud.bamsongi.albammate.chat.match.entity.MatchChatRoom;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatMessageRepository;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatRoomRepository;

/** CHAT-T1: system-message adapter의 멱등 저장과 호출자 트랜잭션 rollback 전파를 검증한다. */
@SpringBootTest
class MatchChatSystemMessageAdapterTest {

	private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");

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

	private final List<Long> partyIds = new ArrayList<>();

	@AfterEach
	void tearDown() {
		partyIds.forEach(
			partyId -> jdbcTemplate.update(
				"delete from match_chat_messages where match_chat_room_id in "
					+ "(select id from match_chat_rooms where party_id = ?)",
				partyId));
		partyIds.forEach(partyId -> jdbcTemplate.update("delete from match_chat_rooms where party_id = ?", partyId));
		partyIds.forEach(partyId -> jdbcTemplate.update("delete from match_parties where id = ?", partyId));
	}

	@Test
	void 같은_이벤트를_서로_다른_호출자_트랜잭션에서_반복_기록해도_시스템_메시지는_한_건으로_수렴한다() {
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
	void 호출자_트랜잭션이_롤백되면_시스템_메시지도_남지_않는다() {
		long partyId = insertActivePartyWithChatRoom();
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

		transactionTemplate.executeWithoutResult(status -> {
			matchChatSystemMessageAdapter.record(partyId, "CHAT_OPENED");
			status.setRollbackOnly();
		});

		assertTrue(matchChatMessageRepository.findAll().isEmpty());
	}

	@Test
	void 채팅방이_준비되지_않았으면_기록하지_않는다() {
		long partyId = insertActivePartyWithoutChatRoom();
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

		assertThrows(
			IllegalStateException.class,
			() -> transactionTemplate.executeWithoutResult(
				status -> matchChatSystemMessageAdapter.record(partyId, "CHAT_OPENED")));
		assertTrue(matchChatMessageRepository.findAll().isEmpty());
	}

	private long insertActivePartyWithChatRoom() {
		long partyId = insertActivePartyWithoutChatRoom();
		matchChatRoomRepository.saveAndFlush(MatchChatRoom.of(partyId));
		return partyId;
	}

	private long insertActivePartyWithoutChatRoom() {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(
				"insert into match_parties "
					+ "(status, preparing_started_at, chat_opened_at, closes_at, created_at, updated_at) "
					+ "values ('ACTIVE', ?, ?, ?, ?, ?)",
				Statement.RETURN_GENERATED_KEYS);
			statement.setTimestamp(1, Timestamp.from(NOW));
			statement.setTimestamp(2, Timestamp.from(NOW));
			statement.setTimestamp(3, Timestamp.from(NOW.plusSeconds(3600)));
			statement.setTimestamp(4, Timestamp.from(NOW));
			statement.setTimestamp(5, Timestamp.from(NOW));
			return statement;
		}, keyHolder);
		long partyId = Objects.requireNonNull(keyHolder.getKey()).longValue();
		partyIds.add(partyId);
		return partyId;
	}
}
