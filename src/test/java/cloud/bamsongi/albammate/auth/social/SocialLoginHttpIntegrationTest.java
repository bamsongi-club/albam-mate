package cloud.bamsongi.albammate.auth.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.RawPassword;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import cloud.bamsongi.albammate.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;

/**
 * T1·T2와 AUTH-05b-AC1~AC4·AC6을 HTTP 경계에서 검증한다.
 *
 * <p>Kakao는 자격증명을 주지 않아 설정되지 않은 제공자로 함께 검증한다.
 */
@SpringBootTest(properties = {
	"app.social.providers.google.client-id=google-test-id",
	"app.social.providers.google.client-secret=google-test-secret",
	"app.social.providers.naver.client-id=naver-test-id",
	"app.social.providers.naver.client-secret=naver-test-secret"
})
@AutoConfigureMockMvc
@Import(StubSocialProvider.Beans.class)
class SocialLoginHttpIntegrationTest {

	private static final String AUTHORIZATION_URI = "/api/auth/social/authorization/";
	private static final String CALLBACK_URI = "/api/auth/social/callback/";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private StubSocialProvider stubSocialProvider;

	@Autowired
	private UserAccountService userAccountService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private MapSessionRepository sessionRepository;

	@BeforeEach
	void resetProviderResponse() {
		stubSocialProvider.respondWith(naverUser(UUID.randomUUID().toString(), "밤톨"));
	}

	@Test
	void 설정된_제공자만_고정_순서로_노출하고_연결_여부는_비로그인에서_거짓이다() throws Exception {
		mockMvc.perform(get("/api/auth/social/providers"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(2))
			.andExpect(jsonPath("$.data[0].provider").value("GOOGLE"))
			.andExpect(jsonPath("$.data[0].linked").value(false))
			.andExpect(jsonPath("$.data[1].provider").value("NAVER"))
			.andExpect(jsonPath("$.data[1].linked").value(false));
	}

	@Test
	void authorization_시작은_제공자로_보내고_callback_URI를_접속한_주소로_계산한다() throws Exception {
		SessionClient session = new SessionClient();

		String location = authorizationRedirect("naver", session);

		assertTrue(location.startsWith("https://nid.naver.com/oauth2.0/authorize"));
		MultiValueMap<String, String> parameters = queryParameters(location);
		assertEquals("naver-test-id", parameters.getFirst("client_id"));
		assertEquals(
			"http://localhost/api/auth/social/callback/naver", parameters.getFirst("redirect_uri"));
		assertNotNull(parameters.getFirst("state"));
	}

	@Test
	void 지원하지_않거나_설정되지_않은_제공자는_외부로_보내지_않는다() throws Exception {
		for (String path : List.of(
			AUTHORIZATION_URI + "kakao",
			AUTHORIZATION_URI + "apple",
			CALLBACK_URI + "kakao",
			CALLBACK_URI + "apple")) {
			mockMvc.perform(get(path))
				.andExpect(status().is3xxRedirection())
				.andExpect(header().string("Location", "/?socialAuth=provider-unavailable#/auth"));
		}
	}

	@Test
	void 첫_로그인_성공은_세션을_교체하고_보호_API와_로그아웃을_사용할_수_있다() throws Exception {
		SessionClient session = new SessionClient();
		String state = state(authorizationRedirect("naver", session));

		MvcResult callback = callback("naver", session, state);

		assertEquals("/?socialAuth=login-success#/home", callback.getResponse().getHeader("Location"));
		mockMvc.perform(get("/api/users/me").cookie(session.cookie))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.nickname").value("밤톨"));
	}

	@Test
	void 같은_외부_식별자의_재로그인은_사용자를_새로_만들지_않는다() throws Exception {
		String subject = UUID.randomUUID().toString();
		stubSocialProvider.respondWith(naverUser(subject, "밤톨"));
		long usersBefore = userRepository.count();

		login("naver");
		long usersAfterFirstLogin = userRepository.count();
		stubSocialProvider.respondWith(naverUser(subject, "다른 닉네임"));
		login("naver");

		assertEquals(usersBefore + 1, usersAfterFirstLogin);
		assertEquals(usersAfterFirstLogin, userRepository.count());
	}

	@Test
	void state가_없거나_다르거나_이미_쓰였으면_로그인을_만들지_않는다() throws Exception {
		long usersBefore = userRepository.count();
		SessionClient session = new SessionClient();
		String state = state(authorizationRedirect("naver", session));

		assertEquals(
			"/?socialAuth=invalid-state#/auth",
			mockMvc.perform(get(CALLBACK_URI + "naver").param("code", "stub-code").cookie(session.cookie))
				.andReturn()
				.getResponse()
				.getHeader("Location"));
		assertEquals(
			"/?socialAuth=invalid-state#/auth",
			callback("naver", session, state + "-tampered").getResponse().getHeader("Location"));
		assertEquals(
			"/?socialAuth=login-success#/home",
			callback("naver", session, state).getResponse().getHeader("Location"));

		SessionClient reusedStateSession = new SessionClient();
		assertEquals(
			"/?socialAuth=invalid-state#/auth",
			callback("naver", reusedStateSession, state).getResponse().getHeader("Location"));
		assertEquals(usersBefore + 1, userRepository.count());
		mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void 사용자가_제공자_동의를_취소하면_로그인을_만들지_않는다() throws Exception {
		long usersBefore = userRepository.count();
		SessionClient session = new SessionClient();
		String state = state(authorizationRedirect("naver", session));

		MvcResult callback = mockMvc.perform(
			get(CALLBACK_URI + "naver").param("error", "access_denied")
				.param("error_description", "user canceled")
				.param("state", state)
				.cookie(session.cookie))
			.andReturn();

		assertEquals("/?socialAuth=canceled#/auth", callback.getResponse().getHeader("Location"));
		assertEquals(usersBefore, userRepository.count());
		mockMvc.perform(get("/api/users/me").cookie(session.cookie)).andExpect(status().isUnauthorized());
	}

	@Test
	void 필수_subject가_없으면_저장_변경_없이_실패한다() throws Exception {
		long usersBefore = userRepository.count();
		stubSocialProvider.respondWith(Map.of("response", Map.of("nickname", "밤톨")));
		SessionClient session = new SessionClient();
		String state = state(authorizationRedirect("naver", session));

		MvcResult callback = callback("naver", session, state);

		assertEquals("/?socialAuth=failed#/auth", callback.getResponse().getHeader("Location"));
		assertEquals(usersBefore, userRepository.count());
		mockMvc.perform(get("/api/users/me").cookie(session.cookie)).andExpect(status().isUnauthorized());
	}

	@Test
	void 신뢰_가능한_이메일이_기존_사용자와_같으면_자동_병합하지_않고_연결을_요구한다() throws Exception {
		String email = "social-link-required-" + UUID.randomUUID() + "@example.com";
		userAccountService.createAccount(
			new CreateUserAccountCommand(
				UserEmail.from(email).orElseThrow(),
				RawPassword.from("123456789012345").orElseThrow(),
				UserNickname.from("기존 사용자").orElseThrow()));
		long usersBefore = userRepository.count();
		stubSocialProvider.respondWith(
			Map.of("sub", UUID.randomUUID().toString(), "email", email, "email_verified", true, "name", "밤톨"));
		SessionClient session = new SessionClient();
		String state = state(authorizationRedirect("google", session));

		MvcResult callback = callback("google", session, state);

		assertEquals("/?socialAuth=link-required#/auth", callback.getResponse().getHeader("Location"));
		assertEquals(usersBefore, userRepository.count());
		mockMvc.perform(get("/api/users/me").cookie(session.cookie)).andExpect(status().isUnauthorized());
	}

	@Test
	void callback_결과에는_code와_token이_없고_세션에_외부_authorized_client가_남지_않는다() throws Exception {
		SessionClient session = new SessionClient();
		String state = state(authorizationRedirect("naver", session));

		MvcResult callback = callback("naver", session, state);

		String location = callback.getResponse().getHeader("Location");
		assertEquals("/?socialAuth=login-success#/home", location);
		assertFalse(location.contains("stub-code"));
		assertFalse(location.contains(StubSocialProvider.ACCESS_TOKEN));
		MapSession storedSession = sessionRepository.findById(session.cookie.getValue());
		assertNotNull(storedSession);
		for (String attributeName : storedSession.getAttributeNames()) {
			assertFalse(
				attributeName.contains("AUTHORIZED_CLIENT"),
				"세션에 외부 authorized client가 남았습니다: " + attributeName);
			assertFalse(
				String.valueOf((Object)storedSession.getAttribute(attributeName))
					.contains(StubSocialProvider.ACCESS_TOKEN),
				"세션에 외부 token이 남았습니다: " + attributeName);
		}
	}

	/**
	 * OAuth 실패는 로그인을 만들지 않을 뿐이며 이미 있는 앱 세션의 인증을 지우지 않는다.
	 *
	 * <p>callback은 {@code permitAll}이고 세션 쿠키는 {@code SameSite=Lax}라 top-level GET에 실려 나간다. 실패
	 * 처리가 저장된 인증을 비우면 공격자가 링크 하나로 로그인한 사용자를 로그아웃시킬 수 있다.
	 *
	 * <p>검사 대상은 실패 처리가 저장소의 인증을 건드리는지 하나뿐이므로 보호 API 대신 저장된 컨텍스트를 직접 본다. 사용자 행을 만들지
	 * 않아 이 클래스가 공유 데이터베이스에 남기는 흔적도 늘리지 않는다.
	 */
	@Test
	void OAuth_실패_callback은_이미_있는_세션_인증을_지우지_않는다() throws Exception {
		SessionClient session = new SessionClient();
		SecurityContext signedIn = authenticatedContext(session);

		mockMvc.perform(get(CALLBACK_URI + "naver").param("code", "stub-code").cookie(session.cookie))
			.andExpect(header().string("Location", "/?socialAuth=invalid-state#/auth"));
		assertEquals(signedIn, storedContext(session), "state 없는 callback이 인증을 지웠습니다");

		callback("naver", session, "tampered-state");
		assertEquals(signedIn, storedContext(session), "state가 다른 callback이 인증을 지웠습니다");

		mockMvc.perform(
			get(CALLBACK_URI + "naver").param("error", "access_denied")
				.param("state", state(authorizationRedirect("naver", session)))
				.cookie(session.cookie))
			.andExpect(header().string("Location", "/?socialAuth=canceled#/auth"));
		assertEquals(signedIn, storedContext(session), "사용자 취소가 인증을 지웠습니다");
	}

	private SecurityContext authenticatedContext(SessionClient session) {
		MapSession storedSession = sessionRepository.createSession();
		session.cookie = new Cookie("JSESSIONID", storedSession.getId());
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(
			UsernamePasswordAuthenticationToken.authenticated(
				new CurrentUserPrincipal(7L), null, AuthorityUtils.NO_AUTHORITIES));
		storedSession.setAttribute(
			HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
		sessionRepository.save(storedSession);
		return context;
	}

	private Object storedContext(SessionClient session) {
		MapSession storedSession = sessionRepository.findById(session.cookie.getValue());
		return storedSession == null ? null
			: storedSession.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
	}

	private void login(String registrationId) throws Exception {
		SessionClient session = new SessionClient();
		String state = state(authorizationRedirect(registrationId, session));
		assertEquals(
			"/?socialAuth=login-success#/home",
			callback(registrationId, session, state).getResponse().getHeader("Location"));
	}

	private String authorizationRedirect(String registrationId, SessionClient session) throws Exception {
		MockHttpServletRequestBuilder request = get(AUTHORIZATION_URI + registrationId);
		if (session.cookie != null) {
			request.cookie(session.cookie);
		}
		MvcResult result = mockMvc.perform(request)
			.andExpect(status().is3xxRedirection())
			.andReturn();
		Cookie sessionCookie = result.getResponse().getCookie("JSESSIONID");
		if (sessionCookie != null) {
			session.cookie = sessionCookie;
		}
		return result.getResponse().getHeader("Location");
	}

	private MvcResult callback(String registrationId, SessionClient session, String state) throws Exception {
		MockHttpServletRequestBuilder request = get(CALLBACK_URI + registrationId)
			.param("code", "stub-code")
			.param("state", state);
		if (session.cookie != null) {
			request.cookie(session.cookie);
		}
		MvcResult result = mockMvc.perform(request).andReturn();
		Cookie sessionCookie = result.getResponse().getCookie("JSESSIONID");
		if (sessionCookie != null) {
			session.cookie = sessionCookie;
		}
		return result;
	}

	private static final class SessionClient {
		private Cookie cookie;
	}

	private String state(String authorizationRedirectLocation) {
		return queryParameters(authorizationRedirectLocation).getFirst("state");
	}

	private MultiValueMap<String, String> queryParameters(String location) {
		MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
		UriComponentsBuilder.fromUri(URI.create(location))
			.build()
			.getQueryParams()
			.forEach((name, values) -> values.forEach(value -> parameters.add(name, decode(value))));
		return parameters;
	}

	private String decode(String value) {
		return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
	}

	private Map<String, Object> naverUser(String subject, String nickname) {
		return Map.of("resultcode", "00", "response", Map.of("id", subject, "nickname", nickname));
	}
}
