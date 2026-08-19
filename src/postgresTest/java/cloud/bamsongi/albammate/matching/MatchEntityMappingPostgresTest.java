package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.matching.entity.MatchPartyParticipant;
import cloud.bamsongi.albammate.matching.entity.MatchPartyParticipantId;
import cloud.bamsongi.albammate.matching.entity.MatchProposalMember;
import cloud.bamsongi.albammate.matching.entity.MatchProposalMemberId;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class MatchEntityMappingPostgresTest extends SharedPostgresIntegrationSupport {

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
	void 게임_없는_MATCH_Entity는_PostgreSQL_저장_재조회와_직접_Repository_query를_통과한다() throws Exception {
		Class<?> requestType = Class.forName("cloud.bamsongi.albammate.matching.entity.MatchRequest");
		Class<?> proposalType = Class.forName("cloud.bamsongi.albammate.matching.entity.MatchProposal");
		Class<?> partyType = Class.forName("cloud.bamsongi.albammate.matching.entity.MatchParty");
		Class<?> proposalMemberType = Class.forName("cloud.bamsongi.albammate.matching.entity.MatchProposalMember");
		Class<?> participantType = Class.forName("cloud.bamsongi.albammate.matching.entity.MatchPartyParticipant");

		EntityType<?> requestEntity = entityManager.getMetamodel().entity(requestType);
		assertEquals("match_requests",
			requestEntity.getJavaType().getAnnotation(jakarta.persistence.Table.class).name());
		assertEquals(jakarta.persistence.EnumType.STRING, requestType.getDeclaredField("status")
			.getAnnotation(jakarta.persistence.Enumerated.class).value());
		assertTrue(requestEntity.getAttributes().stream().anyMatch(attribute -> attribute.getName().equals("userId")));
		assertFalse(requestEntity.getAttributes().stream().anyMatch(attribute -> attribute.getName().equals("gameId")));
		assertFalse(entityManager.getMetamodel().entity(proposalType).getAttributes().stream()
			.anyMatch(attribute -> attribute.getName().equals("gameId")));
		assertFalse(entityManager.getMetamodel().entity(partyType).getAttributes().stream()
			.anyMatch(attribute -> attribute.getName().equals("gameId")));
		assertThrows(NoSuchFieldException.class, () -> requestType.getDeclaredField("gameId"));
		assertThrows(NoSuchFieldException.class, () -> proposalType.getDeclaredField("gameId"));
		assertThrows(NoSuchFieldException.class, () -> partyType.getDeclaredField("gameId"));
		assertTrue(proposalMemberType.isAnnotationPresent(jakarta.persistence.Entity.class));
		assertTrue(participantType.isAnnotationPresent(jakarta.persistence.Entity.class));
		assertTrue(proposalMemberType.getDeclaredField("id").isAnnotationPresent(jakarta.persistence.EmbeddedId.class));
		assertTrue(participantType.getDeclaredField("id").isAnnotationPresent(jakarta.persistence.EmbeddedId.class));

		long userId = insertUser("mapping");
		Class<? extends Enum> requestStatusType = (Class<? extends Enum>)Class.forName(
			"cloud.bamsongi.albammate.matching.MatchRequestStatus");
		Object request = requestType
			.getMethod("create", long.class, int.class, int.class, requestStatusType)
			.invoke(null, userId, 2, 4, Enum.valueOf(requestStatusType, "WAITING"));
		entityManager.persist(request);
		entityManager.flush();
		entityManager.clear();

		Object requestId = requestType.getMethod("getId").invoke(request);
		Object reloaded = entityManager.find(requestType, requestId);
		assertEquals("WAITING", requestType.getMethod("getStatus").invoke(reloaded).toString());
		assertEquals(userId, requestType.getMethod("getUserId").invoke(reloaded));

		long proposalId = insertProposal();
		long partyId = insertParty("ACTIVE");
		assertNotNull(entityManager.find(proposalType, proposalId));
		assertNotNull(entityManager.find(partyType, partyId));
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
	void ParticipantRepository는_대상_Party의_current와_former_participantRef를_보존하고_BlockRepository는_각_방향의_차단을_조회한다()
		throws Exception {
		long firstUserId = insertUser("first");
		long formerUserId = insertUser("former");
		long otherPartyUserId = insertUser("other-party");
		long forwardBlockedUserId = insertUser("forward-blocked");
		long reverseBlockedUserId = insertUser("reverse-blocked");
		long firstPartyId = insertParty("ACTIVE");
		long secondPartyId = insertParty("ACTIVE");
		UUID firstRef = UUID.randomUUID();
		UUID formerRef = UUID.randomUUID();
		UUID otherPartyRef = UUID.randomUUID();
		insertParticipant(firstPartyId, firstUserId, firstRef, false);
		insertParticipant(firstPartyId, formerUserId, formerRef, true);
		insertParticipant(secondPartyId, otherPartyUserId, otherPartyRef, false);
		jdbcTemplate.update(
			"insert into match_blocks (blocker_user_id, blocked_user_id, created_at) values (?, ?, current_timestamp)",
			firstUserId,
			forwardBlockedUserId);
		jdbcTemplate.update(
			"insert into match_blocks (blocker_user_id, blocked_user_id, created_at) values (?, ?, current_timestamp)",
			reverseBlockedUserId,
			firstUserId);

		Class<?> participantRepositoryType = Class.forName(
			"cloud.bamsongi.albammate.matching.repository.MatchPartyParticipantRepository");
		Object participantRepository = applicationContext.getBean(participantRepositoryType);
		Method participantRefs = participantRepositoryType.getMethod("findParticipantRefsByPartyId", Long.class);
		List<?> refs = (List<?>)participantRefs.invoke(participantRepository, firstPartyId);
		assertEquals(2, refs.size());
		assertEquals(Set.of(firstRef, formerRef), Set.copyOf(refs));
		assertFalse(refs.contains(otherPartyRef));

		Class<?> blockRepositoryType = Class
			.forName("cloud.bamsongi.albammate.matching.repository.MatchBlockRepository");
		Object blockRepository = applicationContext.getBean(blockRepositoryType);
		Method blockBetweenUsers = blockRepositoryType.getMethod("existsBlockBetweenUsers", Long.class, Long.class);
		assertTrue((boolean)blockBetweenUsers.invoke(blockRepository, firstUserId, forwardBlockedUserId));
		assertTrue((boolean)blockBetweenUsers.invoke(blockRepository, firstUserId, reverseBlockedUserId));
		assertFalse((boolean)blockBetweenUsers.invoke(blockRepository, firstUserId, otherPartyUserId));
	}

	private long insertUser(String role) {
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, current_timestamp, current_timestamp) returning id",
			Long.class,
			"match-entity-" + role + "-" + UUID.randomUUID() + "@example.com",
			"매칭 " + role);
	}

	private long insertProposal() {
		return jdbcTemplate.queryForObject(
			"insert into match_proposals (party_size, status, respond_by, created_at, updated_at) "
				+ "values (2, 'OPEN', current_timestamp + interval '30 seconds', current_timestamp, current_timestamp) returning id",
			Long.class);
	}

	private long insertParty(String status) {
		return jdbcTemplate.queryForObject(
			"insert into match_parties (status, preparing_started_at, chat_opened_at, closes_at, created_at, updated_at) "
				+ "values (?, current_timestamp, current_timestamp, current_timestamp + interval '1 day', current_timestamp, current_timestamp) returning id",
			Long.class,
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
