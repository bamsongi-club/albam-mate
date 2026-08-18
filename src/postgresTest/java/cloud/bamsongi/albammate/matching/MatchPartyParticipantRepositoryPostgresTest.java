package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
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
import cloud.bamsongi.albammate.matching.entity.MatchPartyParticipant;
import cloud.bamsongi.albammate.matching.repository.MatchPartyParticipantRepository;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class MatchPartyParticipantRepositoryPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final Instant FIXED_TIME = Instant.parse("2026-08-18T00:00:00Z");

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
			"truncate table match_party_participants, match_parties, users restart identity cascade");
	}

	@Test
	void 참가자_ref는_파티_경계를_넘어_조회되지_않고_이탈과_CLOSED_보존_멤버십도_조회된다() {
		long firstUserId = insertUser("first");
		long secondUserId = insertUser("second");
		long formerUserId = insertUser("former");
		long closedUserId = insertUser("closed");
		long firstPartyId = insertParty("ACTIVE");
		long secondPartyId = insertParty("ACTIVE");
		long closedPartyId = insertParty("CLOSED");
		UUID sharedParticipantRef = UUID.fromString("00000000-0000-0000-0000-000000000801");
		UUID formerParticipantRef = UUID.fromString("00000000-0000-0000-0000-000000000802");
		UUID closedParticipantRef = UUID.fromString("00000000-0000-0000-0000-000000000803");

		insertParticipant(firstPartyId, firstUserId, sharedParticipantRef, null);
		insertParticipant(secondPartyId, secondUserId, sharedParticipantRef, null);
		insertParticipant(firstPartyId, formerUserId, formerParticipantRef, FIXED_TIME.plusSeconds(60));
		insertParticipant(closedPartyId, closedUserId, closedParticipantRef, null);

		MatchPartyParticipant firstPartyParticipant = participantRepository
			.findByPartyIdAndParticipantRef(firstPartyId, sharedParticipantRef)
			.orElseThrow();
		MatchPartyParticipant secondPartyParticipant = participantRepository
			.findByPartyIdAndParticipantRef(secondPartyId, sharedParticipantRef)
			.orElseThrow();
		assertEquals(firstPartyId, firstPartyParticipant.getId().getPartyId());
		assertEquals(firstUserId, firstPartyParticipant.getId().getUserId());
		assertEquals(secondPartyId, secondPartyParticipant.getId().getPartyId());
		assertEquals(secondUserId, secondPartyParticipant.getId().getUserId());
		assertFalse(
			participantRepository.findByPartyIdAndParticipantRef(secondPartyId, formerParticipantRef).isPresent());

		MatchPartyParticipant formerParticipant = participantRepository
			.findByPartyIdAndParticipantRef(firstPartyId, formerParticipantRef)
			.orElseThrow();
		assertNotNull(formerParticipant.getLeftAt());
		assertEquals(FIXED_TIME.plusSeconds(60), formerParticipant.getLeftAt());
		MatchPartyParticipant formerMembership = participantRepository
			.findParticipantByPartyIdAndUserId(firstPartyId, formerUserId)
			.orElseThrow();
		assertEquals(formerParticipantRef, formerMembership.getParticipantRef());
		assertNotNull(formerMembership.getLeftAt());

		MatchPartyParticipant closedParticipant = participantRepository
			.findByPartyIdAndParticipantRef(closedPartyId, closedParticipantRef)
			.orElseThrow();
		assertEquals(closedPartyId, closedParticipant.getId().getPartyId());
		MatchPartyParticipant closedMembership = participantRepository
			.findParticipantByPartyIdAndUserId(closedPartyId, closedUserId)
			.orElseThrow();
		assertEquals(closedParticipantRef, closedMembership.getParticipantRef());
		assertTrue(isClosedRetentionPeriod(closedPartyId));
	}

	private long insertUser(String role) {
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?) returning id",
			Long.class,
			"match-participant-" + role + "@example.com",
			"매칭 " + role,
			Timestamp.from(FIXED_TIME),
			Timestamp.from(FIXED_TIME));
	}

	private long insertParty(String status) {
		String lifecycleColumns = switch (status) {
			case "ACTIVE" -> "preparing_started_at, chat_opened_at, closes_at";
			case "CLOSED" -> "preparing_started_at, closed_at, purge_after";
			default -> "preparing_started_at";
		};
		String lifecycleValues = switch (status) {
			case "ACTIVE" -> "?, ?, ?";
			case "CLOSED" -> "?, ?, ?";
			default -> "?";
		};
		return jdbcTemplate.queryForObject(
			"insert into match_parties (status, " + lifecycleColumns
				+ ", created_at, updated_at) values (?, " + lifecycleValues + ", ?, ?) returning id",
			Long.class,
			partyValues(status));
	}

	private Object[] partyValues(String status) {
		Timestamp preparingStartedAt = Timestamp.from(FIXED_TIME);
		Timestamp lifecycleTime = Timestamp.from(FIXED_TIME.plusSeconds(3_600));
		Timestamp retentionEnd = Timestamp.from(FIXED_TIME.plusSeconds(3_600 + 604_800));
		if (status.equals("CLOSED")) {
			return new Object[] {status, preparingStartedAt, lifecycleTime, retentionEnd, preparingStartedAt,
				preparingStartedAt};
		}
		return new Object[] {status, preparingStartedAt, preparingStartedAt, lifecycleTime, preparingStartedAt,
			preparingStartedAt};
	}

	private void insertParticipant(long partyId, long userId, UUID participantRef, Instant leftAt) {
		jdbcTemplate.update(
			"insert into match_party_participants (party_id, user_id, participant_ref, left_at, created_at) values (?, ?, ?, ?, ?)",
			partyId,
			userId,
			participantRef,
			leftAt == null ? null : Timestamp.from(leftAt),
			Timestamp.from(FIXED_TIME));
	}

	private boolean isClosedRetentionPeriod(long partyId) {
		Integer retentionPartyCount = jdbcTemplate.queryForObject(
			"select count(*) from match_parties where id = ? and status = 'CLOSED' and purge_after > ?",
			Integer.class,
			partyId,
			Timestamp.from(FIXED_TIME));
		return retentionPartyCount != null && retentionPartyCount == 1;
	}
}
