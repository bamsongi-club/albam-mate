package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
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
import cloud.bamsongi.albammate.matching.repository.MatchPartyParticipantRepository;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class MatchPartyParticipantRepositoryPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_match_participant_test");

	@Autowired
	private MatchPartyParticipantRepository participantRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute(
			"truncate table match_chat_messages, match_chat_rooms, match_party_participants, match_parties, games, users restart identity cascade");
	}

	@Test
	void 참가자_ref는_같은_Party에서만_해석되고_이탈과_CLOSED_보존_멤버십은_조회된다() {
		long userId = insertUser("member");
		long formerUserId = insertUser("former-member");
		long gameId = insertGame();
		long activePartyId = insertParty(gameId, "ACTIVE");
		long otherPartyId = insertParty(gameId, "ACTIVE");
		long closedPartyId = insertParty(gameId, "CLOSED");
		UUID activeParticipantRef = UUID.randomUUID();
		UUID leftParticipantRef = UUID.randomUUID();
		UUID closedParticipantRef = UUID.randomUUID();
		insertParticipant(activePartyId, userId, activeParticipantRef, null);
		insertParticipant(otherPartyId, userId, UUID.randomUUID(), null);
		insertParticipant(activePartyId, formerUserId, leftParticipantRef, Instant.now());
		insertParticipant(closedPartyId, userId, closedParticipantRef, null);

		assertTrue(
			participantRepository.findByPartyIdAndParticipantRef(activePartyId, activeParticipantRef).isPresent());
		assertFalse(
			participantRepository.findByPartyIdAndParticipantRef(otherPartyId, activeParticipantRef).isPresent());
		assertTrue(participantRepository.findParticipantByPartyIdAndUserId(activePartyId, formerUserId).isPresent());
		assertTrue(participantRepository.findParticipantByPartyIdAndUserId(closedPartyId, userId).isPresent());
	}

	private long insertUser(String role) {
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, current_timestamp, current_timestamp) returning id",
			Long.class,
			"match-participant-" + role + "-" + UUID.randomUUID() + "@example.com",
			"매칭 " + role);
	}

	private long insertGame() {
		return jdbcTemplate.queryForObject(
			"insert into games (bgg_id, name, english_name, supported_player_count, tag, estimated_play_time, description, detail_description, created_at, updated_at) "
				+ "values (?, '참가자 게임', 'Participant Game', '2-4', '전략', '60', '설명', '상세 설명', current_timestamp, current_timestamp) returning id",
			Long.class,
			Math.abs(UUID.randomUUID().getMostSignificantBits()));
	}

	private long insertParty(long gameId, String status) {
		String lifecycleColumns = status.equals("CLOSED")
			? "preparing_started_at, closed_at, purge_after"
			: "preparing_started_at, chat_opened_at, closes_at";
		String lifecycleValues = status.equals("CLOSED")
			? "current_timestamp, current_timestamp, current_timestamp + interval '7 days'"
			: "current_timestamp, current_timestamp, current_timestamp + interval '1 day'";
		return jdbcTemplate.queryForObject(
			"insert into match_parties (game_id, status, " + lifecycleColumns
				+ ", created_at, updated_at) values (?, ?, "
				+ lifecycleValues + ", current_timestamp, current_timestamp) returning id",
			Long.class,
			gameId,
			status);
	}

	private void insertParticipant(long partyId, long userId, UUID participantRef, Instant leftAt) {
		jdbcTemplate.update(
			"insert into match_party_participants (party_id, user_id, participant_ref, left_at, created_at) values (?, ?, ?, ?, current_timestamp)",
			partyId,
			userId,
			participantRef,
			leftAt == null ? null : java.sql.Timestamp.from(leftAt));
	}
}
