package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MatchReportHttpIntegrationTest {

	private static final Instant FIXED_TIME = Instant.parse("2026-08-19T00:00:00Z");

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private ObjectMapper objectMapper;
	@Autowired
	private MeterRegistry meterRegistry;
	private final List<Long> createdUserIds = new ArrayList<>();
	private final List<Long> createdPartyIds = new ArrayList<>();

	@AfterEach
	void tearDown() {
		for (Long partyId : createdPartyIds) {
			jdbcTemplate.update("delete from match_party_participants where party_id = ?", partyId);
			jdbcTemplate.update("delete from match_parties where id = ?", partyId);
		}
		for (Long userId : createdUserIds) {
			jdbcTemplate.update("delete from match_reports where reporter_user_id = ? or reported_user_id = ?", userId,
				userId);
			jdbcTemplate.update("delete from users where id = ?", userId);
		}
	}

	@Test
	void T1_현재와_보존_파티의_다른_참가자_신고는_201_receipt를_반환하고_정확히_7일_보관한다() throws Exception {
		long reporterUserId = insertUser("reporter-t1");
		long reportedUserId = insertUser("reported-t1");
		long partyId = insertActiveParty();
		UUID participantRef = UUID.fromString("00000000-0000-0000-0000-000000000774");
		insertParticipant(partyId, reporterUserId, UUID.fromString("00000000-0000-0000-0000-000000000771"), null);
		insertParticipant(partyId, reportedUserId, participantRef, null);
		long formerReportedUserId = insertUser("former-reported-t1");
		UUID formerParticipantRef = UUID.fromString("00000000-0000-0000-0000-000000000775");
		insertParticipant(partyId, formerReportedUserId, formerParticipantRef, FIXED_TIME.minusSeconds(60));
		long closedReporterUserId = insertUser("closed-reporter-t1");
		long closedReportedUserId = insertUser("closed-reported-t1");
		long closedPartyId = insertClosedParty();
		UUID closedReporterRef = UUID.fromString("00000000-0000-0000-0000-000000000776");
		UUID closedParticipantRef = UUID.fromString("00000000-0000-0000-0000-000000000777");
		insertParticipant(closedPartyId, closedReporterUserId, closedReporterRef, FIXED_TIME.minusSeconds(60));
		insertParticipant(closedPartyId, closedReportedUserId, closedParticipantRef, null);

		mockMvc.perform(post(reportPath(partyId))
			.with(authenticationFor(reporterUserId))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestBody(participantRef, "ABUSE_OR_HARASSMENT")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value(201))
			.andExpect(jsonPath("$.data.receivedAt").exists())
			.andExpect(jsonPath("$.data.alreadyReceived").value(false));
		mockMvc.perform(post(reportPath(partyId))
			.with(authenticationFor(reporterUserId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content(requestBody(formerParticipantRef, "SPAM_OR_SCAM")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.alreadyReceived").value(false));
		mockMvc.perform(post(reportPath(closedPartyId))
			.with(authenticationFor(closedReporterUserId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content(requestBody(closedParticipantRef, "OTHER_RULE_VIOLATION")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.alreadyReceived").value(false));

		assertStoredReport(reporterUserId, reportedUserId, "ABUSE_OR_HARASSMENT");
		assertStoredReport(reporterUserId, formerReportedUserId, "SPAM_OR_SCAM");
		assertStoredReport(closedReporterUserId, closedReportedUserId, "OTHER_RULE_VIOLATION");
		Integer reportCount = jdbcTemplate.queryForObject("select count(*) from match_reports", Integer.class);
		org.junit.jupiter.api.Assertions.assertEquals(3, reportCount);
	}

	@Test
	void T2_멤버십을_확인하지_못하면_FORBIDDEN을_우선하고_실패_경로는_신고를_저장하지_않는다() throws Exception {
		long reporterUserId = insertUser("reporter-t2");
		long reportedUserId = insertUser("reported-t2");
		long nonMemberUserId = insertUser("outsider-t2");
		long partyId = insertActiveParty();
		UUID reporterParticipantRef = UUID.fromString("00000000-0000-0000-0000-000000000772");
		UUID participantRef = UUID.fromString("00000000-0000-0000-0000-000000000773");
		insertParticipant(partyId, reporterUserId, reporterParticipantRef, null);
		insertParticipant(partyId, reportedUserId, participantRef, null);

		mockMvc.perform(post(reportPath(partyId))
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestBody(participantRef, "SPAM_OR_SCAM")))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post(reportPath(partyId))
			.with(authenticationFor(reporterUserId))
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestBody(participantRef, "SPAM_OR_SCAM")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));
		mockMvc.perform(post(reportPath(partyId))
			.with(authenticationFor(nonMemberUserId))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestBody(participantRef, "SPAM_OR_SCAM")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));
		mockMvc.perform(post(reportPath(partyId))
			.with(authenticationFor(reporterUserId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content(requestBody(UUID.randomUUID(), "SPAM_OR_SCAM")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value(ErrorCode.MATCH_PARTICIPANT_NOT_FOUND.getCode()));
		mockMvc.perform(post(reportPath(partyId))
			.with(authenticationFor(reporterUserId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content(requestBody(reporterParticipantRef, "SPAM_OR_SCAM")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));
		mockMvc.perform(post(reportPath(partyId))
			.with(authenticationFor(reporterUserId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content(rawRequestBody(participantRef.toString(), "UNSUPPORTED_REASON")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		mockMvc.perform(post(reportPath(partyId))
			.with(authenticationFor(reporterUserId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"participantRef\":null,\"reason\":\"SPAM_OR_SCAM\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));

		Integer reportCount = jdbcTemplate.queryForObject("select count(*) from match_reports", Integer.class);
		org.junit.jupiter.api.Assertions.assertEquals(0, reportCount);
	}

	@Test
	void T3_보존_중_재신고는_기존_사유와_시각을_보존하고_200으로_수렴한다() throws Exception {
		long reporterUserId = insertUser("reporter-t3");
		long reportedUserId = insertUser("reported-t3");
		long partyId = insertActiveParty();
		UUID participantRef = UUID.fromString("00000000-0000-0000-0000-000000000773");
		insertParticipant(partyId, reporterUserId, UUID.fromString("00000000-0000-0000-0000-000000000774"), null);
		insertParticipant(partyId, reportedUserId, participantRef, null);

		mockMvc.perform(post(reportPath(partyId))
			.with(authenticationFor(reporterUserId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content(requestBody(participantRef, "ABUSE_OR_HARASSMENT")))
			.andExpect(status().isCreated());
		Timestamp originalReportedAt = jdbcTemplate.queryForObject(
			"select reported_at from match_reports", Timestamp.class);
		Timestamp originalPurgeAfter = jdbcTemplate.queryForObject(
			"select purge_after from match_reports", Timestamp.class);
		MvcResult repeatedReport = mockMvc.perform(post(reportPath(partyId))
			.with(authenticationFor(reporterUserId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content(requestBody(participantRef, "SPAM_OR_SCAM")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.alreadyReceived").value(true))
			.andReturn();

		String reason = jdbcTemplate.queryForObject("select reason from match_reports", String.class);
		Timestamp repeatedReportedAt = jdbcTemplate.queryForObject(
			"select reported_at from match_reports", Timestamp.class);
		Timestamp repeatedPurgeAfter = jdbcTemplate.queryForObject(
			"select purge_after from match_reports", Timestamp.class);
		Integer reportCount = jdbcTemplate.queryForObject("select count(*) from match_reports", Integer.class);
		org.junit.jupiter.api.Assertions.assertEquals("ABUSE_OR_HARASSMENT", reason);
		org.junit.jupiter.api.Assertions.assertEquals(1, reportCount);
		org.junit.jupiter.api.Assertions.assertEquals(originalReportedAt.toInstant(), repeatedReportedAt.toInstant());
		org.junit.jupiter.api.Assertions.assertEquals(originalPurgeAfter.toInstant(), repeatedPurgeAfter.toInstant());
		String receivedAt = objectMapper.readTree(repeatedReport.getResponse().getContentAsString())
			.path("data").path("receivedAt").asText();
		org.junit.jupiter.api.Assertions.assertEquals(
			originalReportedAt.toInstant(), OffsetDateTime.parse(receivedAt).toInstant());
	}

	@Test
	void T6_receipt은_내부_식별자와_사유를_노출하지_않고_신고만_저장한다() throws Exception {
		long reporterUserId = insertUser("reporter-t6");
		long reportedUserId = insertUser("reported-t6");
		long partyId = insertActiveParty();
		UUID participantRef = UUID.fromString("00000000-0000-0000-0000-000000000776");
		insertParticipant(partyId, reporterUserId, UUID.fromString("00000000-0000-0000-0000-000000000775"), null);
		insertParticipant(partyId, reportedUserId, participantRef, null);
		int blockCount = countRows("match_blocks");
		int requestCount = countRows("match_requests");
		int proposalCount = countRows("match_proposals");
		int partyCount = countRows("match_parties");
		int participantCount = countRows("match_party_participants");
		int chatRoomCount = countRows("match_chat_rooms");
		List<ListAppender<ILoggingEvent>> appenders = attachReportAppenders();
		Set<String> sensitiveValues = Set.of(
			String.valueOf(reporterUserId),
			String.valueOf(reportedUserId),
			participantRef.toString(),
			"OTHER_RULE_VIOLATION");
		try {

			mockMvc.perform(post(reportPath(partyId))
				.with(authenticationFor(reporterUserId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(requestBody(participantRef, "OTHER_RULE_VIOLATION")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.reporterUserId").doesNotExist())
				.andExpect(jsonPath("$.data.reportedUserId").doesNotExist())
				.andExpect(jsonPath("$.data.participantRef").doesNotExist())
				.andExpect(jsonPath("$.data.reason").doesNotExist());

			org.junit.jupiter.api.Assertions.assertEquals(blockCount, countRows("match_blocks"));
			org.junit.jupiter.api.Assertions.assertEquals(requestCount, countRows("match_requests"));
			org.junit.jupiter.api.Assertions.assertEquals(proposalCount, countRows("match_proposals"));
			org.junit.jupiter.api.Assertions.assertEquals(partyCount, countRows("match_parties"));
			org.junit.jupiter.api.Assertions.assertEquals(participantCount, countRows("match_party_participants"));
			org.junit.jupiter.api.Assertions.assertEquals(chatRoomCount, countRows("match_chat_rooms"));
			assertSensitiveValuesAreAbsentFromLogs(appenders, sensitiveValues);
			assertSensitiveValuesAreAbsentFromMetricTags(sensitiveValues);
		} finally {
			detachReportAppenders(appenders);
		}
	}

	private int countRows(String tableName) {
		return jdbcTemplate.queryForObject("select count(*) from " + tableName, Integer.class);
	}

	private List<ListAppender<ILoggingEvent>> attachReportAppenders() {
		Logger rootLogger = (Logger)org.slf4j.LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		rootLogger.addAppender(appender);
		return List.of(appender);
	}

	private void detachReportAppenders(List<ListAppender<ILoggingEvent>> appenders) {
		Logger rootLogger = (Logger)org.slf4j.LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		for (ListAppender<ILoggingEvent> appender : appenders) {
			rootLogger.detachAppender(appender);
			appender.stop();
		}
	}

	private void assertSensitiveValuesAreAbsentFromLogs(
		List<ListAppender<ILoggingEvent>> appenders, Set<String> sensitiveValues) {
		for (ListAppender<ILoggingEvent> appender : appenders) {
			for (ILoggingEvent event : appender.list) {
				String structuredValues = event.getKeyValuePairs().toString();
				String mdcValues = event.getMDCPropertyMap().toString();
				for (String sensitiveValue : sensitiveValues) {
					org.junit.jupiter.api.Assertions.assertFalse(event.getFormattedMessage().contains(sensitiveValue));
					org.junit.jupiter.api.Assertions.assertFalse(structuredValues.contains(sensitiveValue));
					org.junit.jupiter.api.Assertions.assertFalse(mdcValues.contains(sensitiveValue));
				}
			}
		}
	}

	private void assertSensitiveValuesAreAbsentFromMetricTags(Set<String> sensitiveValues) {
		for (io.micrometer.core.instrument.Meter meter : meterRegistry.getMeters()) {
			for (io.micrometer.core.instrument.Tag tag : meter.getId().getTags()) {
				boolean prohibitedKey = Set.of("reporterUserId", "reportedUserId", "participantRef", "reason")
					.contains(tag.getKey());
				org.junit.jupiter.api.Assertions.assertFalse(prohibitedKey);
				org.junit.jupiter.api.Assertions.assertFalse(sensitiveValues.contains(tag.getValue()));
			}
		}
	}

	private long insertUser(String suffix) {
		String uniqueEmail = suffix + "-" + UUID.randomUUID() + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?)",
			uniqueEmail, suffix, Timestamp.from(FIXED_TIME), Timestamp.from(FIXED_TIME));
		Long userId = jdbcTemplate.queryForObject("select max(id) from users", Long.class);
		createdUserIds.add(userId);
		return userId;
	}

	private long insertActiveParty() {
		Instant closesAt = FIXED_TIME.plus(1, ChronoUnit.DAYS);
		jdbcTemplate.update(
			"insert into match_parties (status, preparing_started_at, chat_opened_at, closes_at, created_at, updated_at) values ('ACTIVE', ?, ?, ?, ?, ?)",
			Timestamp.from(FIXED_TIME), Timestamp.from(FIXED_TIME), Timestamp.from(closesAt),
			Timestamp.from(FIXED_TIME), Timestamp.from(FIXED_TIME));
		Long partyId = jdbcTemplate.queryForObject("select max(id) from match_parties", Long.class);
		createdPartyIds.add(partyId);
		return partyId;
	}

	private long insertClosedParty() {
		Instant closedAt = FIXED_TIME.minus(1, ChronoUnit.HOURS);
		Instant purgeAfter = closedAt.plus(7, ChronoUnit.DAYS);
		jdbcTemplate.update(
			"insert into match_parties (status, preparing_started_at, closed_at, purge_after, created_at, updated_at) values ('CLOSED', ?, ?, ?, ?, ?)",
			Timestamp.from(FIXED_TIME.minus(1, ChronoUnit.DAYS)), Timestamp.from(closedAt), Timestamp.from(purgeAfter),
			Timestamp.from(FIXED_TIME.minus(1, ChronoUnit.DAYS)), Timestamp.from(FIXED_TIME.minus(1, ChronoUnit.DAYS)));
		Long partyId = jdbcTemplate.queryForObject("select max(id) from match_parties", Long.class);
		createdPartyIds.add(partyId);
		return partyId;
	}

	private void assertStoredReport(long reporterUserId, long reportedUserId, String reason) {
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from match_reports where reporter_user_id = ? and reported_user_id = ? and reason = ?",
			Integer.class, reporterUserId, reportedUserId, reason));
		Timestamp reportedAt = jdbcTemplate.queryForObject(
			"select reported_at from match_reports where reporter_user_id = ? and reported_user_id = ?",
			Timestamp.class, reporterUserId, reportedUserId);
		Timestamp purgeAfter = jdbcTemplate.queryForObject(
			"select purge_after from match_reports where reporter_user_id = ? and reported_user_id = ?",
			Timestamp.class, reporterUserId, reportedUserId);
		assertTrue(reportedAt != null && purgeAfter != null);
		assertEquals(reportedAt.toInstant().plus(7, ChronoUnit.DAYS), purgeAfter.toInstant());
	}

	private void insertParticipant(long partyId, long userId, UUID participantRef, Instant leftAt) {
		jdbcTemplate.update(
			"insert into match_party_participants (party_id, user_id, participant_ref, left_at, created_at) values (?, ?, ?, ?, ?)",
			partyId, userId, participantRef, leftAt == null ? null : Timestamp.from(leftAt),
			Timestamp.from(FIXED_TIME));
	}

	private RequestPostProcessor authenticationFor(long userId) {
		return authentication(
			new UsernamePasswordAuthenticationToken(
				new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
	}

	private String reportPath(long partyId) {
		return "/api/matches/parties/" + partyId + "/reports";
	}

	private String requestBody(UUID participantRef, String reason) {
		return rawRequestBody(participantRef.toString(), reason);
	}

	private String rawRequestBody(String participantRef, String reason) {
		return "{\"participantRef\":\"" + participantRef + "\",\"reason\":\"" + reason + "\"}";
	}
}
