package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class MatchPartyAccessQueryPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_match_access_test");

	@Autowired
	private ApplicationContext applicationContext;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute(
			"truncate table match_chat_messages, match_chat_rooms, match_party_participants, match_parties, games, users restart identity cascade");
	}

	@Test
	void ACTIVE이고_나가지_않은_본인_참가_관계만_MATCH_채팅_접근으로_허용한다() throws Exception {
		long memberId = insertUser("member");
		long formerMemberId = insertUser("former");
		long outsiderId = insertUser("outsider");
		long gameId = insertGame();
		long activePartyId = insertParty(gameId, "ACTIVE");
		long preparingPartyId = insertParty(gameId, "PREPARING");
		long closedPartyId = insertParty(gameId, "CLOSED");
		insertParticipant(activePartyId, memberId, false);
		insertParticipant(preparingPartyId, memberId, false);
		insertParticipant(closedPartyId, memberId, false);
		insertParticipant(activePartyId, formerMemberId, true);

		Class<?> accessQueryType = Class.forName(
			"cloud.bamsongi.albammate.matching.contract.MatchPartyAccessQuery");
		Object accessQuery = applicationContext.getBean(accessQueryType);
		Method hasActiveAccess = accessQueryType.getMethod("hasActiveAccess", long.class, long.class);

		assertTrue((boolean)hasActiveAccess.invoke(accessQuery, memberId, activePartyId));
		assertFalse((boolean)hasActiveAccess.invoke(accessQuery, memberId, preparingPartyId));
		assertFalse((boolean)hasActiveAccess.invoke(accessQuery, memberId, closedPartyId));
		assertFalse((boolean)hasActiveAccess.invoke(accessQuery, formerMemberId, activePartyId));
		assertFalse((boolean)hasActiveAccess.invoke(accessQuery, outsiderId, activePartyId));
	}

	private long insertUser(String role) {
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, current_timestamp, current_timestamp) returning id",
			Long.class,
			"match-access-" + role + "-" + UUID.randomUUID() + "@example.com",
			"매칭 " + role);
	}

	private long insertGame() {
		return jdbcTemplate.queryForObject(
			"insert into games (bgg_id, name, english_name, supported_player_count, tag, estimated_play_time, description, detail_description, created_at, updated_at) "
				+ "values (?, '접근 게임', 'Access Game', '2-4', '전략', '60', '설명', '상세 설명', current_timestamp, current_timestamp) returning id",
			Long.class,
			Math.abs(UUID.randomUUID().getMostSignificantBits()));
	}

	private long insertParty(long gameId, String status) {
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
			"insert into match_parties (game_id, status, " + lifecycleColumns
				+ ", created_at, updated_at) values (?, ?, "
				+ lifecycleValues + ", current_timestamp, current_timestamp) returning id",
			Long.class,
			gameId,
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
