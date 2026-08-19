package cloud.bamsongi.albammate.chat.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.chat.match.entity.MatchChatMessage;
import cloud.bamsongi.albammate.chat.match.entity.MatchChatRoom;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatMessageRepository;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatRoomRepository;

/**
 * CHAT-T1이 의존하는 {@link MatchChatRoom}·{@link MatchChatMessage} 정적 팩터리와 repository 매핑이
 * PostgreSQL 유일 제약과 함께 동작하는지 검증한다.
 *
 * <p>제약·인덱스 존재 자체는 {@code MatchSchemaPostgresTest}가 소유하므로, 이 테스트는 JPA 엔티티·repository가
 * find-then-create 경합에서도 그 제약에 안전하게 기대는지만 확인한다.
 */
@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class MatchChatPersistencePostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_match_chat_persistence_test");

	@Autowired
	private MatchChatRoomRepository matchChatRoomRepository;
	@Autowired
	private MatchChatMessageRepository matchChatMessageRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void tearDown() {
		jdbcTemplate
			.execute("truncate table match_chat_messages, match_chat_rooms, match_parties restart identity cascade");
	}

	@Test
	void 같은_partyId의_두번째_MatchChatRoom_저장은_uq_match_chat_rooms_party로_거절된다() {
		long partyId = insertPreparingParty();
		matchChatRoomRepository.saveAndFlush(MatchChatRoom.of(partyId));

		assertUniqueViolation(
			"uq_match_chat_rooms_party",
			() -> matchChatRoomRepository.saveAndFlush(MatchChatRoom.of(partyId)));
	}

	@Test
	void 같은_채팅방_같은_이벤트의_두번째_시스템_메시지_저장은_uq_match_chat_messages_system_event로_거절된다() {
		long partyId = insertActivePartyWithChatRoom();
		MatchChatRoom room = matchChatRoomRepository.findByPartyId(partyId).orElseThrow();
		matchChatMessageRepository.saveAndFlush(
			MatchChatMessage.createSystemMessage(room.getId(), MatchChatSystemEventKey.CHAT_OPENED, "채팅이 열렸습니다.", NOW));

		assertUniqueViolation(
			"uq_match_chat_messages_system_event",
			() -> matchChatMessageRepository.saveAndFlush(
				MatchChatMessage.createSystemMessage(
					room.getId(), MatchChatSystemEventKey.CHAT_OPENED, "중복 저장 시도", NOW)));
	}

	@Test
	void 서로_다른_채팅방의_같은_이벤트는_각각_저장된다() {
		long firstPartyId = insertActivePartyWithChatRoom();
		long secondPartyId = insertActivePartyWithChatRoom();
		MatchChatRoom firstRoom = matchChatRoomRepository.findByPartyId(firstPartyId).orElseThrow();
		MatchChatRoom secondRoom = matchChatRoomRepository.findByPartyId(secondPartyId).orElseThrow();

		matchChatMessageRepository.saveAndFlush(
			MatchChatMessage.createSystemMessage(
				firstRoom.getId(), MatchChatSystemEventKey.CHAT_OPENED, "채팅이 열렸습니다.", NOW));
		matchChatMessageRepository.saveAndFlush(
			MatchChatMessage.createSystemMessage(
				secondRoom.getId(), MatchChatSystemEventKey.CHAT_OPENED, "채팅이 열렸습니다.", NOW));

		assertEquals(2, matchChatMessageRepository.count());
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
		matchChatRoomRepository.saveAndFlush(MatchChatRoom.of(partyId));
		return partyId;
	}

	private void assertUniqueViolation(String expectedConstraint, org.junit.jupiter.api.function.Executable operation) {
		DataIntegrityViolationException exception = assertThrows(DataIntegrityViolationException.class, operation);
		SQLException sqlException = findSqlException(exception);
		assertEquals("23505", sqlException.getSQLState());
		assertEquals(true, exception.getMessage().contains(expectedConstraint));
	}

	private SQLException findSqlException(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof SQLException sqlException) {
				return sqlException;
			}
		}
		throw new AssertionError("Expected a PostgreSQL SQLException cause", throwable);
	}
}
