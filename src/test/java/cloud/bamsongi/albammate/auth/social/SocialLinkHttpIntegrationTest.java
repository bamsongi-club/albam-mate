package cloud.bamsongi.albammate.auth.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.RawPassword;
import cloud.bamsongi.albammate.user.contract.SocialAccountService;
import cloud.bamsongi.albammate.user.contract.SocialIdentity;
import cloud.bamsongi.albammate.user.contract.SocialProvider;
import cloud.bamsongi.albammate.user.contract.UserAccount;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import jakarta.servlet.http.Cookie;

/**
 * T1~T5와 {@code AUTH-05c-AC1}~{@code AUTH-05c-AC5}를 HTTP 경계에서 검증한다.
 *
 * <p>Kakao는 자격증명을 주지 않아 설정되지 않은 제공자로 함께 검증한다. 같은 사용자·같은 외부 식별자의 수렴은 연결 시작이 먼저 거절하므로 이
 * 경계에서는 도달하지 않고, 사용자 모듈의 연결 계약 테스트가 담당한다.
 */
@SpringBootTest(properties = {
	"app.social.providers.google.client-id=google-test-id",
	"app.social.providers.google.client-secret=google-test-secret",
	"app.social.providers.naver.client-id=naver-test-id",
	"app.social.providers.naver.client-secret=naver-test-secret"
})
@AutoConfigureMockMvc
@Import(StubSocialProvider.Beans.class)
class SocialLinkHttpIntegrationTest {

	private static final String LINK_URI = "/api/users/me/social-accounts/{provider}/link";
	private static final String CALLBACK_URI = "/api/auth/social/callback/";
	private static final Pattern AUTHORIZATION_URI_PATTERN = Pattern.compile("\"authorizationUri\":\"([^\"]+)\"");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private StubSocialProvider stubSocialProvider;

	@Autowired
	private UserAccountService userAccountService;

	@Autowired
	private SocialAccountService socialAccountService;

	@Autowired
	private MapSessionRepository sessionRepository;

	@Test
	void 연결_시작은_설정된_제공자의_nonce_결속_authorization_경로를_반환한다() throws Exception {
		SessionClient session = signedInSession();

		String authorizationUri = startLink("naver", session);

		assertTrue(
			authorizationUri.startsWith("/api/auth/social/authorization/naver?linkNonce="),
			"연결 전용 nonce가 authorization URI에 없습니다: " + authorizationUri);
	}

	@Test
	void 지원하지_않거나_설정되지_않은_제공자의_연결_시작은_거절한다() throws Exception {
		SessionClient session = signedInSession();

		for (String registrationId : List.of("kakao", "apple")) {
			perform(link(registrationId, session), session)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.code").value("SOCIAL_PROVIDER_NOT_AVAILABLE"));
		}
	}

	@Test
	void 같은_제공자를_이미_연결한_사용자의_연결_시작은_거절한다() throws Exception {
		UserAccount account = createAccount();
		socialAccountService.link(
			account.id(),
			new SocialIdentity(
				SocialProvider.NAVER, UUID.randomUUID().toString(), Optional.empty(), Optional.empty(),
				java.util.Optional.empty()));

		SessionClient session = signedInSession(account);
		perform(link("naver", session), session)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.status").value(409))
			.andExpect(jsonPath("$.code").value("SOCIAL_ACCOUNT_ALREADY_LINKED"))
			.andExpect(jsonPath("$.message").value("해당 소셜 계정 제공자가 이미 연결되어 있습니다."));
	}

	@Test
	void 연결_시작_뒤_callback은_현재_사용자에게_외부_신원을_연결한다() throws Exception {
		UserAccount account = createAccount();
		SessionClient session = signedInSession(account);
		String subject = UUID.randomUUID().toString();
		stubSocialProvider.respondWith(naverUser(subject, "다른 닉네임"));

		String authorizationUri = startLink("naver", session);
		String state = state(authorizationRedirect(authorizationUri, session));

		assertEquals(
			"/?socialAuth=link-success#/profile",
			callback("naver", session, state).getResponse().getHeader("Location"));
		assertEquals(
			Set.of(SocialProvider.NAVER), socialAccountService.linkedProviders(account.id()));
	}

	@Test
	void 다른_제공자의_callback은_연결하지_않고_연결_의도를_폐기한다() throws Exception {
		UserAccount account = createAccount();
		SessionClient session = signedInSession(account);
		String state = state(authorizationRedirect(startLink("naver", session), session));

		assertEquals(
			"/?socialAuth=invalid-state#/profile",
			callback("google", session, state).getResponse().getHeader("Location"));
		assertEquals(
			"/?socialAuth=invalid-state#/profile",
			callback("naver", session, state).getResponse().getHeader("Location"));
		assertEquals(Set.of(), socialAccountService.linkedProviders(account.id()));
		perform(get("/api/users/me"), session).andExpect(status().isOk());
	}

	@Test
	void 연결_성공_뒤_callback_state_재사용은_연결하지_않고_기존_로그인을_유지한다() throws Exception {
		UserAccount account = createAccount();
		SessionClient session = signedInSession(account);
		stubSocialProvider.respondWith(naverUser(UUID.randomUUID().toString(), "밤톨"));
		String state = state(authorizationRedirect(startLink("naver", session), session));

		assertEquals(
			"/?socialAuth=link-success#/profile",
			callback("naver", session, state).getResponse().getHeader("Location"));
		assertEquals(
			"/?socialAuth=invalid-state#/profile",
			callback("naver", session, state).getResponse().getHeader("Location"));
		assertEquals(Set.of(SocialProvider.NAVER), socialAccountService.linkedProviders(account.id()));
		perform(get("/api/users/me"), session).andExpect(status().isOk());
	}

	@Test
	void 필수_subject가_없는_연결_callback은_실패_뒤_의도를_폐기하고_기존_로그인을_유지한다() throws Exception {
		UserAccount account = createAccount();
		SessionClient session = signedInSession(account);
		stubSocialProvider.respondWith(Map.of("response", Map.of("nickname", "밤톨")));
		String state = state(authorizationRedirect(startLink("naver", session), session));

		assertEquals(
			"/?socialAuth=failed#/profile",
			callback("naver", session, state).getResponse().getHeader("Location"));
		perform(get("/api/users/me"), session).andExpect(status().isOk());
		assertEquals(Set.of(), socialAccountService.linkedProviders(account.id()));
		assertEquals(
			"/?socialAuth=invalid-state#/profile",
			callback("naver", session, state).getResponse().getHeader("Location"));
		assertEquals(Set.of(), socialAccountService.linkedProviders(account.id()));
	}

	@Test
	void 제공자_이메일의_중복과_부재와_무관하게_callback_직전_사용자에게_연결한다() throws Exception {
		String duplicateEmail = "social-link-duplicate@example.com";
		createAccount(duplicateEmail);
		UserAccount emailDuplicateTarget = createAccount();
		stubSocialProvider.respondWith(googleUser(UUID.randomUUID().toString(), duplicateEmail));
		SessionClient duplicateSession = signedInSession(emailDuplicateTarget);

		String duplicateState = state(authorizationRedirect(startLink("google", duplicateSession), duplicateSession));
		assertEquals(
			"/?socialAuth=link-success#/profile",
			callback("google", duplicateSession, duplicateState).getResponse().getHeader("Location"));
		assertEquals(
			Set.of(SocialProvider.GOOGLE), socialAccountService.linkedProviders(emailDuplicateTarget.id()));

		UserAccount emailAbsentTarget = createAccount();
		stubSocialProvider.respondWith(googleUserWithoutEmail(UUID.randomUUID().toString()));
		SessionClient absentSession = signedInSession(emailAbsentTarget);

		String absentState = state(authorizationRedirect(startLink("google", absentSession), absentSession));
		assertEquals(
			"/?socialAuth=link-success#/profile",
			callback("google", absentSession, absentState).getResponse().getHeader("Location"));
		assertEquals(
			Set.of(SocialProvider.GOOGLE), socialAccountService.linkedProviders(emailAbsentTarget.id()));
	}

	@Test
	void 일반_로그인_시작은_남은_연결_의도를_취소하고_auth_모드로_완료한다() throws Exception {
		UserAccount account = createAccount();
		SessionClient session = signedInSession(account);
		stubSocialProvider.respondWith(naverUser(UUID.randomUUID().toString(), "밤톨"));

		String linkState = state(authorizationRedirect(startLink("naver", session), session));
		String loginState = state(
			authorizationRedirect(
				SocialClientRegistrationRepository.AUTHORIZATION_BASE_URI + "/naver", session));

		assertEquals(
			"/?socialAuth=login-success#/home",
			callback("naver", session, loginState).getResponse().getHeader("Location"));
		assertEquals(Set.of(), socialAccountService.linkedProviders(account.id()));
		assertEquals(
			"/?socialAuth=invalid-state#/profile",
			callback("naver", session, linkState).getResponse().getHeader("Location"));
		assertEquals(Set.of(), socialAccountService.linkedProviders(account.id()));

		UserAccount canceledAccount = createAccount();
		SessionClient canceledSession = signedInSession(canceledAccount);
		String canceledLinkState = state(authorizationRedirect(startLink("naver", canceledSession), canceledSession));
		String canceledLoginState = state(
			authorizationRedirect(
				SocialClientRegistrationRepository.AUTHORIZATION_BASE_URI + "/naver", canceledSession));
		perform(
			get(CALLBACK_URI + "naver").param("error", "access_denied").param("state", canceledLoginState),
			canceledSession)
			.andExpect(header().string("Location", "/?socialAuth=canceled#/auth"));
		assertEquals(
			"/?socialAuth=invalid-state#/profile",
			callback("naver", canceledSession, canceledLinkState).getResponse().getHeader("Location"));
		assertEquals(Set.of(), socialAccountService.linkedProviders(canceledAccount.id()));
	}

	@Test
	void 다른_사용자에게_연결된_외부_식별자는_기존_연결을_보존한_채_충돌이_된다() throws Exception {
		UserAccount owner = createAccount();
		String subject = UUID.randomUUID().toString();
		socialAccountService.link(
			owner.id(),
			new SocialIdentity(SocialProvider.NAVER, subject, Optional.empty(), Optional.empty(),
				java.util.Optional.empty()));

		UserAccount other = createAccount();
		SessionClient session = signedInSession(other);
		stubSocialProvider.respondWith(naverUser(subject, "밤톨"));

		String state = state(authorizationRedirect(startLink("naver", session), session));

		assertEquals(
			"/?socialAuth=link-conflict#/profile",
			callback("naver", session, state).getResponse().getHeader("Location"));
		assertEquals(Set.of(SocialProvider.NAVER), socialAccountService.linkedProviders(owner.id()));
		assertEquals(Set.of(), socialAccountService.linkedProviders(other.id()));
		perform(get("/api/users/me"), session).andExpect(status().isOk());
	}

	@Test
	void 연결_취소와_state_불일치는_연결_시도_화면으로_돌아가고_기존_로그인을_유지한다() throws Exception {
		UserAccount account = createAccount();
		SessionClient session = signedInSession(account);

		String state = state(authorizationRedirect(startLink("naver", session), session));
		perform(
			get(CALLBACK_URI + "naver").param("error", "access_denied").param("state", state),
			session)
			.andExpect(header().string("Location", "/?socialAuth=canceled#/profile"));
		perform(get("/api/users/me"), session).andExpect(status().isOk());
		assertEquals(Set.of(), socialAccountService.linkedProviders(account.id()));

		String originalLinkState = state(authorizationRedirect(startLink("naver", session), session));
		assertEquals(
			"/?socialAuth=invalid-state#/profile",
			callback("naver", session, "tampered-state").getResponse().getHeader("Location"));
		perform(get("/api/users/me"), session).andExpect(status().isOk());
		assertEquals(
			"/?socialAuth=invalid-state#/profile",
			callback("naver", session, originalLinkState).getResponse().getHeader("Location"));
		assertEquals(Set.of(), socialAccountService.linkedProviders(account.id()));
	}

	@Test
	void 연결_취소_뒤_같은_callback을_재사용해도_연결하지_않는다() throws Exception {
		UserAccount account = createAccount();
		SessionClient session = signedInSession(account);
		String state = state(authorizationRedirect(startLink("naver", session), session));

		perform(
			get(CALLBACK_URI + "naver").param("error", "access_denied").param("state", state),
			session)
			.andExpect(header().string("Location", "/?socialAuth=canceled#/profile"));
		assertEquals(
			"/?socialAuth=invalid-state#/profile",
			callback("naver", session, state).getResponse().getHeader("Location"));

		perform(get("/api/users/me"), session).andExpect(status().isOk());
		assertEquals(Set.of(), socialAccountService.linkedProviders(account.id()));
	}

	@Test
	void 취소된_link_state_표식은_고정_상한을_지키고_만료된다() {
		MutableClock clock = new MutableClock(Instant.parse("2026-08-04T00:00:00Z"));
		SocialLinkIntentStore store = new SocialLinkIntentStore(clock);
		MockHttpSession session = new MockHttpSession();
		String latestState = null;

		for (int index = 0; index <= SocialLinkIntentStore.MAX_DISCARDED_STATES; index++) {
			MockHttpServletRequest request = request(session);
			SocialLinkIntent intent = SocialLinkIntent.create(SocialProvider.NAVER, 1L);
			latestState = "discarded-state-" + index;
			store.save(request, intent);
			store.bindAuthorizationRequest(request, latestState, intent.nonce());
			store.discardPendingIntent(request);
		}

		Map<?, ?> discardedStates = (Map<?, ?>)session.getAttribute(
			SocialLinkIntentStore.DISCARDED_STATES_ATTRIBUTE);
		assertEquals(SocialLinkIntentStore.MAX_DISCARDED_STATES, discardedStates.size());

		MockHttpServletRequest delayedCallback = request(session);
		store.activateForCallback(delayedCallback, latestState);
		assertTrue(store.isLinkCallback(delayedCallback));
		assertTrue(store.consumeCallbackIntent(delayedCallback).isEmpty());

		clock.advance(SocialLinkIntentStore.DISCARDED_STATE_TTL);
		MockHttpServletRequest expiredCallback = request(session);
		store.activateForCallback(expiredCallback, latestState);
		assertFalse(store.isLinkCallback(expiredCallback));
		assertEquals(0, discardedStates.size());
	}

	@Test
	void 연결_의도_store는_결속되지_않은_입력과_세션_없는_요청을_무시한다() {
		SocialLinkIntentStore store = new SocialLinkIntentStore(Clock.systemUTC());
		MockHttpServletRequest noSessionRequest = new MockHttpServletRequest();

		store.bindAuthorizationRequest(noSessionRequest, null, null);
		assertFalse(store.discardPendingIntent(noSessionRequest));
		store.activateForCallback(noSessionRequest, "state");
		assertFalse(store.isLinkCallback(noSessionRequest));

		MockHttpSession session = new MockHttpSession();
		MockHttpServletRequest request = request(session);
		SocialLinkIntent intent = SocialLinkIntent.create(SocialProvider.NAVER, 1L);
		store.save(request, intent);
		store.bindAuthorizationRequest(request, null, intent.nonce());
		store.bindAuthorizationRequest(request, "state", null);
		store.bindAuthorizationRequest(request, "state", "mismatched-nonce");
		store.bindAuthorizationRequest(request, "state", intent.nonce());

		store.activateForCallback(request, "different-state");
		assertFalse(store.isLinkCallback(request));
		assertTrue(store.consumeCallbackIntent(request).isEmpty());
	}

	@Test
	void 제공자_목록은_로그인_사용자의_실제_연결_여부를_반영한다() throws Exception {
		UserAccount account = createAccount();
		socialAccountService.link(
			account.id(),
			new SocialIdentity(
				SocialProvider.NAVER, UUID.randomUUID().toString(), Optional.empty(), Optional.empty(),
				java.util.Optional.empty()));

		perform(get("/api/auth/social/providers"), signedInSession(account))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].provider").value("GOOGLE"))
			.andExpect(jsonPath("$.data[0].linked").value(false))
			.andExpect(jsonPath("$.data[1].provider").value("NAVER"))
			.andExpect(jsonPath("$.data[1].linked").value(true));
	}

	@Test
	void 연결_의도를_만든_사용자와_다른_사용자의_세션이면_연결하지_않는다() throws Exception {
		UserAccount starter = createAccount();
		UserAccount other = createAccount();
		SessionClient session = signedInSession(starter);
		stubSocialProvider.respondWith(naverUser(UUID.randomUUID().toString(), "밤톨"));

		String state = state(authorizationRedirect(startLink("naver", session), session));
		authenticate(session, other);

		assertEquals(
			"/?socialAuth=invalid-state#/profile",
			callback("naver", session, state).getResponse().getHeader("Location"));
		assertEquals(Set.of(), socialAccountService.linkedProviders(starter.id()));
		assertEquals(Set.of(), socialAccountService.linkedProviders(other.id()));
	}

	@Test
	void 비로그인과_CSRF_오류의_연결_시작은_거절한다() throws Exception {
		mockMvc.perform(post(LINK_URI, "naver"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.status").value(401))
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

		perform(post(LINK_URI, "naver"), signedInSession())
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.status").value(403))
			.andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
	}

	@Test
	void 연결_성공은_세션_ID와_CSRF를_교체한다() throws Exception {
		UserAccount account = createAccount();
		SessionClient session = signedInSession(account);
		stubSocialProvider.respondWith(naverUser(UUID.randomUUID().toString(), "밤톨"));

		Cookie staleCsrf = csrfCookie(session);
		String sessionIdBeforeLink = session.sessionId();
		String state = state(authorizationRedirect(startLink("naver", session), session));
		assertEquals(
			"/?socialAuth=link-success#/profile",
			callback("naver", session, state).getResponse().getHeader("Location"));

		assertNotEquals(sessionIdBeforeLink, session.sessionId(), "연결 성공이 세션 ID를 교체하지 않았습니다");
		perform(
			post("/api/auth/logout").cookie(staleCsrf).header("X-XSRF-TOKEN", staleCsrf.getValue()),
			session)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

		Cookie refreshedCsrf = csrfCookie(session);
		perform(
			patch("/api/users/me").cookie(refreshedCsrf)
				.header("X-XSRF-TOKEN", refreshedCsrf.getValue())
				.contentType("application/json")
				.content("{\"nickname\":\"연결 후 닉네임\"}"),
			session)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.nickname").value("연결 후 닉네임"));
		perform(
			post("/api/auth/logout").cookie(refreshedCsrf).header("X-XSRF-TOKEN", refreshedCsrf.getValue()),
			session)
			.andExpect(status().isOk());
	}

	private String startLink(String registrationId, SessionClient session) throws Exception {
		String body = perform(link(registrationId, session), session)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andReturn()
			.getResponse()
			.getContentAsString();
		Matcher matcher = AUTHORIZATION_URI_PATTERN.matcher(body);
		assertTrue(matcher.find(), "authorizationUri가 없습니다: " + body);
		return matcher.group(1);
	}

	private String authorizationRedirect(String authorizationUri, SessionClient session) throws Exception {
		String location = perform(get(authorizationUri), session)
			.andExpect(status().is3xxRedirection())
			.andReturn()
			.getResponse()
			.getHeader("Location");
		assertTrue(
			location != null && location.startsWith("https://"),
			"연결 시작이 OAuth 제공자로 이동하지 않았습니다: " + location);
		return location;
	}

	private MvcResult callback(String registrationId, SessionClient session, String state) throws Exception {
		return perform(
			get("/api/auth/social/callback/" + registrationId).param("code", "stub-code").param("state", state),
			session)
			.andReturn();
	}

	/**
	 * 세션 쿠키를 실어 보내고 응답이 새 쿠키를 주면 갱신한다.
	 *
	 * <p>Spring Session이 세션을 관리하므로 {@code MockHttpSession} 주입은 쓰이지 않는다. 연결 성공처럼 세션 ID를 교체하는 응답
	 * 뒤에도 같은 흐름을 이어가려면 응답 쿠키를 따라가야 한다.
	 */
	private ResultActions perform(MockHttpServletRequestBuilder request, SessionClient session) throws Exception {
		if (session.cookie != null) {
			request.cookie(session.cookie);
		}
		ResultActions actions = mockMvc.perform(request);
		Cookie refreshed = actions.andReturn().getResponse().getCookie("JSESSIONID");
		if (refreshed != null) {
			session.cookie = refreshed;
		}
		return actions;
	}

	private static final class SessionClient {

		private Cookie cookie;

		private String sessionId() {
			return cookie == null ? null : cookie.getValue();
		}
	}

	private String state(String authorizationRedirectLocation) {
		String state = UriComponentsBuilder.fromUri(URI.create(authorizationRedirectLocation))
			.build()
			.getQueryParams()
			.getFirst("state");
		return URLDecoder.decode(state, StandardCharsets.UTF_8);
	}

	private Map<String, Object> naverUser(String subject, String nickname) {
		return Map.of("resultcode", "00", "response", Map.of("id", subject, "nickname", nickname));
	}

	private Map<String, Object> googleUser(String subject, String email) {
		return Map.of("sub", subject, "email", email, "email_verified", true, "name", "밤톨");
	}

	private Map<String, Object> googleUserWithoutEmail(String subject) {
		return Map.of("sub", subject, "name", "밤톨");
	}

	private MockHttpServletRequestBuilder link(String registrationId, SessionClient session) throws Exception {
		Cookie csrf = csrfCookie(session);
		return post(LINK_URI, registrationId).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
	}

	private Cookie csrfCookie(SessionClient session) throws Exception {
		Cookie csrf = perform(get("/api/auth/csrf"), session)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getCookie("XSRF-TOKEN");
		assertNotNull(csrf, "CSRF 토큰 쿠키가 없습니다");
		return csrf;
	}

	private UserAccount createAccount() {
		return createAccount("link-" + UUID.randomUUID() + "@example.com");
	}

	private UserAccount createAccount(String email) {
		return userAccountService.createAccount(
			new CreateUserAccountCommand(
				UserEmail.from(email).orElseThrow(),
				RawPassword.from("123456789012345").orElseThrow(),
				UserNickname.from("밤톨").orElseThrow()));
	}

	private SessionClient signedInSession() {
		return signedInSession(createAccount());
	}

	private SessionClient signedInSession(UserAccount account) {
		SessionClient session = new SessionClient();
		authenticate(session, account);
		return session;
	}

	/**
	 * Spring Session 저장소의 세션에 인증을 심고 그 ID를 쿠키로 들려보낸다.
	 *
	 * <p>이미 세션이 있으면 새로 만들지 않고 사용자만 바꾼다. 남아 있는 연결 의도는 그대로 두어야 의도를 만든 사용자와 다른 사용자의 callback을
	 * 구분하는 경로를 검증할 수 있다.
	 */
	private void authenticate(SessionClient session, UserAccount account) {
		MapSession storedSession = session.cookie == null ? sessionRepository.createSession()
			: sessionRepository.findById(session.sessionId());
		assertNotNull(storedSession, "세션이 저장소에 없습니다");
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(
			UsernamePasswordAuthenticationToken.authenticated(
				new CurrentUserPrincipal(account.id()), null, AuthorityUtils.NO_AUTHORITIES));
		storedSession.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
		sessionRepository.save(storedSession);
		session.cookie = new Cookie("JSESSIONID", storedSession.getId());
	}

	private MockHttpServletRequest request(MockHttpSession session) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setSession(session);
		return request;
	}

	private static final class MutableClock extends Clock {

		private Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		void advance(java.time.Duration duration) {
			instant = instant.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}
