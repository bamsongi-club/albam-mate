package cloud.bamsongi.albammate.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import cloud.bamsongi.albammate.assistant.dto.AssistantConsentDecision;
import cloud.bamsongi.albammate.assistant.dto.AssistantConsentRequest;
import cloud.bamsongi.albammate.assistant.dto.AssistantDraftCreateRequest;
import cloud.bamsongi.albammate.assistant.service.AssistantConsentService;
import cloud.bamsongi.albammate.assistant.service.AssistantDraftService;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;
import cloud.bamsongi.albammate.user.contract.UserRowLockPort;
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
	@MockitoBean
	private UserRowLockPort userRowLockPort;

	@BeforeEach
	void setUpUserRowLockPort() {
		stubUserRowLockPort();
	}

	@Test
	void T5_PostgreSQL은_사용자별_ACTIVE_초안_부분_유일_인덱스를_등록한다() {
		assertEquals(1, jdbcTemplate.queryForObject("""
			select count(*) from pg_index index_catalog
			join pg_class index_class on index_class.oid = index_catalog.indexrelid
			where index_class.relname = 'uq_assistant_drafts_active_user'
			  and index_catalog.indisunique and index_catalog.indpred is not null
			""", Integer.class));
		java.util.Map<String, Object> index = jdbcTemplate.queryForMap("""
			select pg_get_indexdef(index_catalog.indexrelid, 1, true) as key_expression,
			       pg_get_expr(index_catalog.indpred, index_catalog.indrelid) as predicate
			from pg_index index_catalog
			join pg_class index_class on index_class.oid = index_catalog.indexrelid
			where index_class.relname = 'uq_assistant_drafts_active_user'
			""");
		assertEquals("user_id", index.get("key_expression"));
		String predicate = ((String)index.get("predicate")).toLowerCase(java.util.Locale.ROOT);
		assertTrue(predicate.contains("status"));
		assertTrue(predicate.contains("'active'"));

		long userId = grantUser("assistant-draft-pg-active-unique@example.com");
		draftService.create(userId, request("첫 ACTIVE", null));
		java.sql.Timestamp timestamp = java.sql.Timestamp.from(Instant.parse("2030-01-01T12:00:00Z"));
		org.junit.jupiter.api.Assertions.assertThrows(DataIntegrityViolationException.class,
			() -> jdbcTemplate.update("""
				insert into assistant_drafts (
					user_id, draft_version, status, room_type, title, experience_level, is_rulemaster_led,
					region, capacity, start_at, expires_at, created_at, updated_at
				) values (?, 0, 'ACTIVE', 'PERSON_FOCUSED', '두 번째 ACTIVE', 'ALL_LEVELS', false,
					'홍대', 3, ?, ?, ?, ?)
				""", userId, timestamp, timestamp, timestamp, timestamp));
	}

	@Test
	void T5_PostgreSQL_동시_confirm은_한_Room과_ChatRoom_결과로_재생된다() throws Exception {
		long userId = grantUser("assistant-draft-pg-concurrent@example.com");
		long draftId = draftService.create(userId, request("동시 확인", "카페")).draftId();
		try (var executor = Executors.newFixedThreadPool(2)) {
			var futures = executor.invokeAll(java.util.List.of(
				confirmTask(userId, draftId, "postgres-concurrent-key"),
				confirmTask(userId, draftId, "postgres-concurrent-key")));
			AssistantDraftService.ConfirmOutcome first = futures.get(0).get();
			AssistantDraftService.ConfirmOutcome second = futures.get(1).get();
			long firstRoomId = first.result().roomId();
			long secondRoomId = second.result().roomId();
			assertEquals(firstRoomId, secondRoomId);
			assertEquals(first.result().chatRoomId(), second.result().chatRoomId());
			assertEquals(firstRoomId, jdbcTemplate.queryForObject("select room_id from chat_rooms where id = ?",
				Long.class, first.result().chatRoomId()));
		}
		assertEquals(1,
			jdbcTemplate.queryForObject("select count(*) from rooms where host_user_id = ?", Integer.class, userId));
		assertEquals(1,
			jdbcTemplate.queryForObject(
				"select count(*) from chat_rooms where room_id in (select id from rooms where host_user_id = ?)",
				Integer.class, userId));
	}

	@Test
	void T6_PostgreSQL_철회가_초안_생성_직전_동의를_무효화하면_ACTIVE를_남기지_않는다() throws Exception {
		long userId = userRepository
			.saveAndFlush(User.create("assistant-draft-pg-revoke-race@example.com", "{bcrypt}hash", "AI 초안 PG"))
			.getId();
		CountDownLatch createReachedUserLock = new CountDownLatch(1);
		CountDownLatch releaseCreateUserLock = new CountDownLatch(1);
		CountDownLatch revokeStarted = new CountDownLatch(1);
		AtomicReference<Thread> createThread = new AtomicReference<>();
		reset(userRowLockPort);
		doAnswer(invocation -> {
			if (Thread.currentThread() == createThread.get()) {
				createReachedUserLock.countDown();
				assertTrue(releaseCreateUserLock.await(10, TimeUnit.SECONDS), "초안 생성 USERS 잠금을 해제하지 못했습니다.");
			}
			Collection<Long> userIds = invocation.getArgument(0);
			return userRepository.findExistingUsersForUpdateInAscendingOrder(userIds).stream()
				.map(User::getId)
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		}).when(userRowLockPort).lockExistingUsersInAscendingOrder(org.mockito.ArgumentMatchers.anyCollection());
		consentService.changeConsent(userId,
			new AssistantConsentRequest(AssistantConsentDecision.GRANT, "AI-01-CONSENT-V1"));

		try (var executor = Executors.newFixedThreadPool(2)) {
			var createFuture = executor.submit(() -> {
				createThread.set(Thread.currentThread());
				draftService.create(userId, request("철회 경합", null));
			});
			assertTrue(createReachedUserLock.await(10, TimeUnit.SECONDS), "초안 생성이 USERS 잠금 지점에 도달하지 못했습니다.");

			var revokeFuture = executor.submit(() -> {
				revokeStarted.countDown();
				return consentService.changeConsent(userId,
					new AssistantConsentRequest(AssistantConsentDecision.REVOKE, null));
			});
			assertTrue(revokeStarted.await(10, TimeUnit.SECONDS), "동의 철회 경합을 시작하지 못했습니다.");
			releaseCreateUserLock.countDown();

			try {
				createFuture.get(10, TimeUnit.SECONDS);
			} catch (java.util.concurrent.ExecutionException exception) {
				BusinessException createError = assertInstanceOf(BusinessException.class, exception.getCause());
				assertEquals(ErrorCode.ASSISTANT_CONSENT_REQUIRED, createError.getErrorCode());
			}
			revokeFuture.get(10, TimeUnit.SECONDS);
			assertEquals(0, jdbcTemplate.queryForObject(
				"select count(*) from assistant_drafts where user_id = ? and status = 'ACTIVE'", Integer.class,
				userId));
		} finally {
			releaseCreateUserLock.countDown();
			reset(userRowLockPort);
			stubUserRowLockPort();
		}
	}

	private void stubUserRowLockPort() {
		doAnswer(invocation -> lockUsers(invocation.getArgument(0)))
			.when(userRowLockPort).lockExistingUsersInAscendingOrder(org.mockito.ArgumentMatchers.anyCollection());
	}

	private java.util.Set<Long> lockUsers(Collection<Long> userIds) {
		return userRepository.findExistingUsersForUpdateInAscendingOrder(userIds).stream()
			.map(User::getId)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
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

	@Test
	void T3_PostgreSQL_active_조회는_만료를_논리적으로만_판정한다() {
		long userId = grantUser("assistant-draft-pg-active-query@example.com");
		long draftId = draftService.create(userId, request("활성 조회", null)).draftId();
		assertEquals(draftId, draftService.getActive(userId).orElseThrow().draftId());
		jdbcTemplate.update("update assistant_drafts set expires_at = ? where id = ?",
			java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")), draftId);

		BusinessException error = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
			() -> draftService.getActive(userId));
		assertEquals(ErrorCode.ASSISTANT_DRAFT_EXPIRED, error.getErrorCode());
		assertEquals("ACTIVE", jdbcTemplate.queryForObject("select status from assistant_drafts where id = ?",
			String.class, draftId));
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
