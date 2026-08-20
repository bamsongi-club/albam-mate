package cloud.bamsongi.albammate.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import cloud.bamsongi.albammate.assistant.dto.AssistantConsentDecision;
import cloud.bamsongi.albammate.assistant.dto.AssistantConsentRequest;
import cloud.bamsongi.albammate.assistant.dto.AssistantDraftCreateRequest;
import cloud.bamsongi.albammate.assistant.service.AssistantConsentService;
import cloud.bamsongi.albammate.assistant.service.AssistantDraftService;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;

/** AI-03 migration의 PostgreSQL 전용 부분 유일 인덱스를 직접 검증한다. */
@SpringBootTest(properties = {
	"app.assistant.enabled=true", "app.assistant.no-retention-verified=true",
	"app.assistant.no-training-verified=true", "app.assistant.policy-version=OPENAI-POLICY-2026-08",
	"app.assistant.policy-url=https://openai.com/policies/api-data-usage-policies"
})
class AssistantDraftPostgresTest extends SharedPostgresIntegrationSupport {

	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private AssistantConsentService consentService;
	@Autowired
	private AssistantDraftService draftService;

	@Test
	void T5_PostgreSQL은_사용자별_ACTIVE_초안_부분_유일_인덱스를_등록한다() {
		assertEquals(1, jdbcTemplate.queryForObject("""
			select count(*) from pg_index index_catalog
			join pg_class index_class on index_class.oid = index_catalog.indexrelid
			where index_class.relname = 'uq_assistant_drafts_active_user'
			  and index_catalog.indisunique and index_catalog.indpred is not null
			""", Integer.class));
	}

	@Test
	void T5_PostgreSQL_동시_confirm은_한_Room과_ChatRoom_결과로_재생된다() throws Exception {
		long userId = grantUser("assistant-draft-pg-concurrent@example.com");
		long draftId = draftService.create(userId, request("동시 확인", "카페")).draftId();
		try (var executor = Executors.newFixedThreadPool(2)) {
			var futures = executor.invokeAll(java.util.List.of(
				confirmTask(userId, draftId, "postgres-concurrent-key"),
				confirmTask(userId, draftId, "postgres-concurrent-key")));
			long firstRoomId = futures.get(0).get().result().roomId();
			long secondRoomId = futures.get(1).get().result().roomId();
			assertEquals(firstRoomId, secondRoomId);
		}
		assertEquals(1,
			jdbcTemplate.queryForObject("select count(*) from rooms where host_user_id = ?", Integer.class, userId));
		assertEquals(1,
			jdbcTemplate.queryForObject(
				"select count(*) from chat_rooms where room_id in (select id from rooms where host_user_id = ?)",
				Integer.class, userId));
	}

	@Test
	void T3_PostgreSQL_새_초안은_기존_ACTIVE를_DISCARDED로_flush한_뒤_하나만_남긴다() {
		long userId = grantUser("assistant-draft-pg-lifecycle@example.com");
		long firstDraftId = draftService.create(userId, request("기존 활성", null)).draftId();
		long secondDraftId = draftService.create(userId, request("새 활성", null)).draftId();
		assertEquals("DISCARDED", jdbcTemplate.queryForObject("select status from assistant_drafts where id = ?",
			String.class, firstDraftId));
		assertEquals("ACTIVE", jdbcTemplate.queryForObject("select status from assistant_drafts where id = ?",
			String.class, secondDraftId));
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from assistant_drafts where user_id = ? and status = 'ACTIVE'", Integer.class, userId));
	}

	private Callable<AssistantDraftService.ConfirmOutcome> confirmTask(long userId, long draftId, String key) {
		return () -> {
			withCurrentUser(userId);
			try {
				return draftService.confirm(userId, draftId, 0, key);
			} finally {
				SecurityContextHolder.clearContext();
			}
		};
	}

	private long grantUser(String email) {
		long userId = userRepository.saveAndFlush(User.create(email, "{bcrypt}hash", "AI 초안 PG")).getId();
		consentService.changeConsent(userId,
			new AssistantConsentRequest(AssistantConsentDecision.GRANT, "AI-01-CONSENT-V1"));
		return userId;
	}

	private AssistantDraftCreateRequest request(String title, String place) {
		return new AssistantDraftCreateRequest("PERSON_FOCUSED", title, null, null, "ALL_LEVELS", false,
			Instant.parse("2030-01-01T12:00:00Z"), "홍대", place, 3);
	}

	private void withCurrentUser(long userId) {
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
			new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
	}
}
