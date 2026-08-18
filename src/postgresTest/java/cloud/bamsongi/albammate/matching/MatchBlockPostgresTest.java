package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
@AutoConfigureMockMvc
class MatchBlockPostgresTest {

	private static final Instant FIXED_TIME = Instant.parse("2026-08-19T00:00:00Z");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4");

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void 같은_대상_동시_차단은_하나의_관계와_성공_응답으로_수렴한다() throws Exception {
		long requesterUserId = insertUser("요청자");
		long blockedUserId = insertUser("차단대상");
		long partyId = insertActiveParty();
		insertParticipant(partyId, requesterUserId, UUID.randomUUID());
		UUID blockedRef = UUID.randomUUID();
		insertParticipant(partyId, blockedUserId, blockedRef);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Callable<Integer> blockRequest = () -> mockMvc.perform(
				put("/api/matches/parties/{partyId}/participants/{participantRef}/block", partyId, blockedRef)
					.with(authenticationFor(requesterUserId)).with(csrf()))
				.andReturn()
				.getResponse()
				.getStatus();
			Future<Integer> first = executor.submit(blockRequest);
			Future<Integer> second = executor.submit(blockRequest);

			assertEquals(200, get(first));
			assertEquals(200, get(second));
		}

		Integer blockCount = jdbcTemplate.queryForObject(
			"select count(*) from match_blocks where blocker_user_id = ? and blocked_user_id = ?",
			Integer.class, requesterUserId, blockedUserId);
		assertEquals(1, blockCount);
	}

	private int get(Future<Integer> result) throws InterruptedException, ExecutionException {
		return result.get();
	}

	private long insertUser(String nickname) {
		String email = "match-block-postgres-" + UUID.randomUUID() + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, ?, ?, ?, ?)",
			email, "hash", nickname, Timestamp.from(FIXED_TIME), Timestamp.from(FIXED_TIME));
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	private long insertActiveParty() {
		return jdbcTemplate.queryForObject(
			"insert into match_parties (status, preparing_started_at, chat_opened_at, closes_at, created_at, updated_at) values (?, ?, ?, ?, ?, ?) returning id",
			Long.class,
			"ACTIVE", Timestamp.from(FIXED_TIME), Timestamp.from(FIXED_TIME),
			Timestamp.from(FIXED_TIME.plusSeconds(3_600)),
			Timestamp.from(FIXED_TIME), Timestamp.from(FIXED_TIME));
	}

	private void insertParticipant(long partyId, long userId, UUID participantRef) {
		jdbcTemplate.update(
			"insert into match_party_participants (party_id, user_id, participant_ref, created_at) values (?, ?, ?, ?)",
			partyId, userId, participantRef, Timestamp.from(FIXED_TIME));
	}

	private RequestPostProcessor authenticationFor(long userId) {
		return authentication(new UsernamePasswordAuthenticationToken(
			new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
	}
}
