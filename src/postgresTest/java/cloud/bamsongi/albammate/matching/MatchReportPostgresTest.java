package cloud.bamsongi.albammate.matching;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
class MatchReportPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final Instant FIXED_TIME = Instant.parse("2026-08-19T00:00:00Z");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_match_report_test");

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute(
			"truncate table match_reports, match_party_participants, match_parties, users restart identity cascade");
	}

	@Test
	void T4_만료된_신고는_PostgreSQL에서_새_사유와_7일_기한으로_원자_교체된다() throws Exception {
		Instant operationTime = Instant.now();
		long reporterUserId = insertUser("reporter-t4");
		long reportedUserId = insertUser("reported-t4");
		long partyId = insertActiveParty();
		UUID participantRef = UUID.fromString("00000000-0000-0000-0000-000000000779");
		insertParticipant(partyId, reporterUserId, UUID.fromString("00000000-0000-0000-0000-000000000777"));
		insertParticipant(partyId, reportedUserId, participantRef);
		jdbcTemplate.update(
			"insert into match_reports (reporter_user_id, reported_user_id, reason, reported_at, purge_after) values (?, ?, 'SPAM_OR_SCAM', ?, ?)",
			reporterUserId, reportedUserId, Timestamp.from(operationTime.minusSeconds(8 * 24 * 60 * 60)),
			Timestamp.from(operationTime.minusSeconds(1)));

		mockMvc.perform(post("/api/matches/parties/" + partyId + "/reports")
			.with(authenticationFor(reporterUserId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"participantRef\":\"" + participantRef + "\",\"reason\":\"HATE_OR_DISCRIMINATION\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.alreadyReceived").value(false));

		String reason = jdbcTemplate.queryForObject("select reason from match_reports", String.class);
		Integer reportCount = jdbcTemplate.queryForObject("select count(*) from match_reports", Integer.class);
		org.junit.jupiter.api.Assertions.assertEquals("HATE_OR_DISCRIMINATION", reason);
		org.junit.jupiter.api.Assertions.assertEquals(1, reportCount);
	}

	private long insertUser(String suffix) {
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?) returning id",
			Long.class, suffix + "@example.com", suffix, Timestamp.from(FIXED_TIME), Timestamp.from(FIXED_TIME));
	}

	private long insertActiveParty() {
		return jdbcTemplate.queryForObject(
			"insert into match_parties (status, preparing_started_at, chat_opened_at, closes_at, created_at, updated_at) values ('ACTIVE', ?, ?, ?, ?, ?) returning id",
			Long.class, Timestamp.from(FIXED_TIME), Timestamp.from(FIXED_TIME),
			Timestamp.from(FIXED_TIME.plusSeconds(86400)), Timestamp.from(FIXED_TIME), Timestamp.from(FIXED_TIME));
	}

	private void insertParticipant(long partyId, long userId, UUID participantRef) {
		jdbcTemplate.update(
			"insert into match_party_participants (party_id, user_id, participant_ref, created_at) values (?, ?, ?, ?)",
			partyId, userId, participantRef, Timestamp.from(FIXED_TIME));
	}

	private RequestPostProcessor authenticationFor(long userId) {
		return authentication(
			new UsernamePasswordAuthenticationToken(
				new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
	}
}
