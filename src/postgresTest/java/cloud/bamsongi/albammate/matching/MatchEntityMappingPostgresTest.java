package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.matching.entity.MatchPartyParticipant;
import cloud.bamsongi.albammate.matching.entity.MatchPartyParticipantId;
import cloud.bamsongi.albammate.matching.entity.MatchProposalMember;
import cloud.bamsongi.albammate.matching.entity.MatchProposalMemberId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class MatchEntityMappingPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_match_entity_test");

	@Autowired
	private EntityManager entityManager;
	@Autowired
	private ApplicationContext applicationContext;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute(
			"truncate table match_chat_messages, match_chat_rooms, match_party_participants, match_parties, "
				+ "match_proposal_members, match_proposals, match_idempotency_records, match_blocks, match_reports, match_requests, games, users restart identity cascade");
	}

	@Test
	@Transactional
	void MATCH_Entity와_복합_ID는_저장_후_재조회에서_문자열_enum과_PK_FK_매핑을_보존한다() throws Exception {
		Class<?> requestType = Class.forName("cloud.bamsongi.albammate.matching.entity.MatchRequest");
		Class<?> proposalMemberType = Class.forName("cloud.bamsongi.albammate.matching.entity.MatchProposalMember");
		Class<?> participantType = Class.forName("cloud.bamsongi.albammate.matching.entity.MatchPartyParticipant");

		EntityType<?> requestEntity = entityManager.getMetamodel().entity(requestType);
		assertEquals("match_requests",
			requestEntity.getJavaType().getAnnotation(jakarta.persistence.Table.class).name());
		assertEquals(jakarta.persistence.EnumType.STRING, requestType.getDeclaredField("status")
			.getAnnotation(jakarta.persistence.Enumerated.class).value());
		assertTrue(requestEntity.getAttributes().stream().anyMatch(attribute -> attribute.getName().equals("userId")));
		assertTrue(requestEntity.getAttributes().stream().anyMatch(attribute -> attribute.getName().equals("gameId")));
		assertTrue(proposalMemberType.isAnnotationPresent(jakarta.persistence.Entity.class));
		assertTrue(participantType.isAnnotationPresent(jakarta.persistence.Entity.class));
		assertTrue(proposalMemberType.getDeclaredField("id").isAnnotationPresent(jakarta.persistence.EmbeddedId.class));
		assertTrue(participantType.getDeclaredField("id").isAnnotationPresent(jakarta.persistence.EmbeddedId.class));

		long userId = insertUser("mapping");
		long gameId = insertGame();
		Class<? extends Enum> requestStatusType = (Class<? extends Enum>)Class.forName(
			"cloud.bamsongi.albammate.matching.MatchRequestStatus");
		Object request = requestType
			.getMethod("create", long.class, long.class, int.class, int.class, requestStatusType)
			.invoke(null, userId, gameId, 2, 4, Enum.valueOf(requestStatusType, "WAITING"));
		entityManager.persist(request);
		entityManager.flush();
		entityManager.clear();

		Object requestId = requestType.getMethod("getId").invoke(request);
		Object reloaded = entityManager.find(requestType, requestId);
		assertEquals("WAITING", requestType.getMethod("getStatus").invoke(reloaded).toString());
		assertEquals(userId, requestType.getMethod("getUserId").invoke(reloaded));
		assertEquals(gameId, requestType.getMethod("getGameId").invoke(reloaded));

		long proposalId = insertProposal(gameId);
		long partyId = insertParty(gameId, "ACTIVE");
		Instant respondedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
		MatchProposalMember member = MatchProposalMember.create(
			proposalId,
			((Long)requestId),
			userId,
			MatchProposalResponseStatus.ACCEPTED,
			respondedAt);
		MatchPartyParticipant participant = MatchPartyParticipant.create(
			partyId,
			userId,
			UUID.randomUUID(),
			Instant.now());
		entityManager.persist(member);
		entityManager.persist(participant);
		entityManager.flush();
		entityManager.clear();

		MatchProposalMember reloadedMember = entityManager.find(
			MatchProposalMember.class,
			new MatchProposalMemberId(proposalId, (Long)requestId));
		MatchPartyParticipant reloadedParticipant = entityManager.find(
			MatchPartyParticipant.class,
			new MatchPartyParticipantId(partyId, userId));
		assertEquals(userId, reloadedMember.getUserId());
		assertEquals(MatchProposalResponseStatus.ACCEPTED, reloadedMember.getResponseStatus());
		assertEquals(respondedAt, reloadedMember.getRespondedAt());
		assertEquals(participant.getParticipantRef(), reloadedParticipant.getParticipantRef());
	}

	@Test
	void ParticipantRepository는_대상_Party의_현재_participantRef만_반환하고_BlockRepository는_양방향_차단을_조회한다() throws Exception {
		long firstUserId = insertUser("first");
		long secondUserId = insertUser("second");
		long thirdUserId = insertUser("third");
		long gameId = insertGame();
		long firstPartyId = insertParty(gameId, "ACTIVE");
		long secondPartyId = insertParty(gameId, "ACTIVE");
		UUID firstRef = UUID.randomUUID();
		UUID otherPartyRef = UUID.randomUUID();
		insertParticipant(firstPartyId, firstUserId, firstRef, false);
		insertParticipant(secondPartyId, secondUserId, otherPartyRef, false);
		jdbcTemplate.update(
			"insert into match_blocks (blocker_user_id, blocked_user_id, created_at) values (?, ?, current_timestamp)",
			thirdUserId,
			firstUserId);
		jdbcTemplate.update(
			"insert into match_blocks (blocker_user_id, blocked_user_id, created_at) values (?, ?, current_timestamp)",
			firstUserId,
			thirdUserId);

		Class<?> participantRepositoryType = Class.forName(
			"cloud.bamsongi.albammate.matching.repository.MatchPartyParticipantRepository");
		Object participantRepository = applicationContext.getBean(participantRepositoryType);
		Method participantRefs = participantRepositoryType.getMethod("findCurrentParticipantRefsByPartyId", Long.class);
		List<?> refs = (List<?>)participantRefs.invoke(participantRepository, firstPartyId);
		assertEquals(List.of(firstRef), refs);
		assertFalse(refs.contains(otherPartyRef));

		Class<?> blockRepositoryType = Class
			.forName("cloud.bamsongi.albammate.matching.repository.MatchBlockRepository");
		Object blockRepository = applicationContext.getBean(blockRepositoryType);
		Method blockBetweenUsers = blockRepositoryType.getMethod("existsBlockBetweenUsers", Long.class, Long.class);
		assertTrue((boolean)blockBetweenUsers.invoke(blockRepository, firstUserId, thirdUserId));
		assertFalse((boolean)blockBetweenUsers.invoke(blockRepository, firstUserId, secondUserId));
	}

	private long insertUser(String role) {
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, current_timestamp, current_timestamp) returning id",
			Long.class,
			"match-entity-" + role + "-" + UUID.randomUUID() + "@example.com",
			"매칭 " + role);
	}

	private long insertGame() {
		return jdbcTemplate.queryForObject(
			"insert into games (bgg_id, name, english_name, supported_player_count, tag, estimated_play_time, description, detail_description, created_at, updated_at) "
				+ "values (?, '매핑 게임', 'Mapping Game', '2-4', '전략', '60', '설명', '상세 설명', current_timestamp, current_timestamp) returning id",
			Long.class,
			Math.abs(UUID.randomUUID().getMostSignificantBits()));
	}

	private long insertProposal(long gameId) {
		return jdbcTemplate.queryForObject(
			"insert into match_proposals (game_id, party_size, status, respond_by, created_at, updated_at) "
				+ "values (?, 2, 'OPEN', current_timestamp + interval '30 seconds', current_timestamp, current_timestamp) returning id",
			Long.class,
			gameId);
	}

	private long insertParty(long gameId, String status) {
		return jdbcTemplate.queryForObject(
			"insert into match_parties (game_id, status, preparing_started_at, chat_opened_at, closes_at, created_at, updated_at) "
				+ "values (?, ?, current_timestamp, current_timestamp, current_timestamp + interval '1 day', current_timestamp, current_timestamp) returning id",
			Long.class,
			gameId,
			status);
	}

	private void insertParticipant(long partyId, long userId, UUID participantRef, boolean left) {
		jdbcTemplate.update(
			"insert into match_party_participants (party_id, user_id, participant_ref, left_at, created_at) values (?, ?, ?, ?, current_timestamp)",
			partyId,
			userId,
			participantRef,
			left ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
	}
}
