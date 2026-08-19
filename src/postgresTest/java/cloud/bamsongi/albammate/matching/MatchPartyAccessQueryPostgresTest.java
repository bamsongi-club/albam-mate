package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.matching.contract.MatchPartyAccessQuery;
import cloud.bamsongi.albammate.matching.contract.MatchPartyChatAccess;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class MatchPartyAccessQueryPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_match_access_test");

	@Autowired
	private MatchPartyAccessQuery accessQuery;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute(
			"truncate table match_chat_messages, match_chat_rooms, match_party_participants, match_parties, users restart identity cascade");
	}

	@Test
	void 현재_참가자의_ACTIVE와_PREPARING만_서로_다른_채팅_접근_결과로_반환한다() {
		long memberId = insertUser("member");
		long formerMemberId = insertUser("former");
		long outsiderId = insertUser("outsider");
		long activePartyId = insertParty("ACTIVE");
		long preparingPartyId = insertParty("PREPARING");
		long closedPartyId = insertParty("CLOSED");
		insertParticipant(activePartyId, memberId, false);
		insertParticipant(preparingPartyId, memberId, false);
		insertParticipant(closedPartyId, memberId, false);
		insertParticipant(activePartyId, formerMemberId, true);

		assertEquals(MatchPartyChatAccess.ALLOWED, accessQuery.evaluateChatAccess(memberId, activePartyId));
		assertEquals(MatchPartyChatAccess.NOT_ACTIVE, accessQuery.evaluateChatAccess(memberId, preparingPartyId));
		assertEquals(MatchPartyChatAccess.FORBIDDEN, accessQuery.evaluateChatAccess(memberId, closedPartyId));
		assertEquals(MatchPartyChatAccess.FORBIDDEN, accessQuery.evaluateChatAccess(formerMemberId, activePartyId));
		assertEquals(MatchPartyChatAccess.FORBIDDEN, accessQuery.evaluateChatAccess(outsiderId, activePartyId));
		assertEquals(MatchPartyChatAccess.FORBIDDEN, accessQuery.evaluateChatAccess(memberId, 999_999L));
	}

	private long insertUser(String role) {
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, current_timestamp, current_timestamp) returning id",
			Long.class,
			"match-access-" + role + "-" + UUID.randomUUID() + "@example.com",
			"매칭 " + role);
	}

	private long insertParty(String status) {
		String lifecycleColumns = switch (status) {
			case "ACTIVE" -> "preparing_started_at, chat_opened_at, closes_at";
			case "CLOSED" -> "preparing_started_at, closed_at, purge_after";
			default -> "preparing_started_at";
		};
		String lifecycleValues = switch (status) {
			case "ACTIVE" -> "current_timestamp, current_timestamp, current_timestamp + interval '1 day'";
			case "CLOSED" -> "current_timestamp, current_timestamp, current_timestamp + interval '7 days'";
			default -> "current_timestamp";
		};
		return jdbcTemplate.queryForObject(
			"insert into match_parties (status, " + lifecycleColumns
				+ ", created_at, updated_at) values (?, "
				+ lifecycleValues + ", current_timestamp, current_timestamp) returning id",
			Long.class,
			status);
	}

	private void insertParticipant(long partyId, long userId, boolean left) {
		jdbcTemplate.update(
			"insert into match_party_participants (party_id, user_id, participant_ref, left_at, created_at) values (?, ?, ?, ?, current_timestamp)",
			partyId,
			userId,
			UUID.randomUUID(),
			left ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
	}
}
