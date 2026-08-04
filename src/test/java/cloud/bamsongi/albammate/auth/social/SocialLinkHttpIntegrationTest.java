package cloud.bamsongi.albammate.auth.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
 * T1과 {@code AUTH-05c-AC1}을 HTTP 경계에서 검증한다.
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

	@Test
	void 연결_시작은_설정된_제공자의_authorization_경로만_반환한다() throws Exception {
		MockHttpSession session = signedInSession();

		mockMvc.perform(link("naver", session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.authorizationUri").value("/api/auth/social/authorization/naver"));
	}

	@Test
	void 지원하지_않거나_설정되지_않은_제공자의_연결_시작은_거절한다() throws Exception {
		MockHttpSession session = signedInSession();

		for (String registrationId : List.of("kakao", "apple")) {
			mockMvc.perform(link(registrationId, session))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SOCIAL_PROVIDER_NOT_AVAILABLE"));
		}
	}

	@Test
	void 같은_제공자를_이미_연결한_사용자의_연결_시작은_거절한다() throws Exception {
		UserAccount account = createAccount();
		socialAccountService.link(
			account.id(),
			new SocialIdentity(
				SocialProvider.NAVER, UUID.randomUUID().toString(), Optional.empty(), Optional.empty()));

		mockMvc.perform(link("naver", signedInSession(account)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SOCIAL_ACCOUNT_ALREADY_LINKED"));
	}

	@Test
	void 연결_시작_뒤_callback은_현재_사용자에게_외부_신원을_연결한다() throws Exception {
		UserAccount account = createAccount();
		MockHttpSession session = signedInSession(account);
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
	void 다른_사용자에게_연결된_외부_식별자는_기존_연결을_보존한_채_충돌이_된다() throws Exception {
		UserAccount owner = createAccount();
		String subject = UUID.randomUUID().toString();
		socialAccountService.link(
			owner.id(),
			new SocialIdentity(SocialProvider.NAVER, subject, Optional.empty(), Optional.empty()));

		UserAccount other = createAccount();
		MockHttpSession session = signedInSession(other);
		stubSocialProvider.respondWith(naverUser(subject, "밤톨"));

		String state = state(authorizationRedirect(startLink("naver", session), session));

		assertEquals(
			"/?socialAuth=link-conflict#/profile",
			callback("naver", session, state).getResponse().getHeader("Location"));
		assertEquals(Set.of(SocialProvider.NAVER), socialAccountService.linkedProviders(owner.id()));
		assertEquals(Set.of(), socialAccountService.linkedProviders(other.id()));
	}

	@Test
	void 연결_취소와_state_불일치는_연결_시도_화면으로_돌아가고_기존_로그인을_유지한다() throws Exception {
		UserAccount account = createAccount();
		MockHttpSession session = signedInSession(account);

		String state = state(authorizationRedirect(startLink("naver", session), session));
		mockMvc.perform(
			get(CALLBACK_URI + "naver").param("error", "access_denied").param("state", state).session(session))
			.andExpect(header().string("Location", "/?socialAuth=canceled#/profile"));
		mockMvc.perform(get("/api/users/me").session(session)).andExpect(status().isOk());
		assertEquals(Set.of(), socialAccountService.linkedProviders(account.id()));

		startLink("naver", session);
		assertEquals(
			"/?socialAuth=invalid-state#/profile",
			callback("naver", session, "tampered-state").getResponse().getHeader("Location"));
		mockMvc.perform(get("/api/users/me").session(session)).andExpect(status().isOk());
	}

	@Test
	void 제공자_목록은_로그인_사용자의_실제_연결_여부를_반영한다() throws Exception {
		UserAccount account = createAccount();
		socialAccountService.link(
			account.id(),
			new SocialIdentity(
				SocialProvider.NAVER, UUID.randomUUID().toString(), Optional.empty(), Optional.empty()));

		mockMvc.perform(get("/api/auth/social/providers").session(signedInSession(account)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].provider").value("GOOGLE"))
			.andExpect(jsonPath("$.data[0].linked").value(false))
			.andExpect(jsonPath("$.data[1].provider").value("NAVER"))
			.andExpect(jsonPath("$.data[1].linked").value(true));
	}

	private String startLink(String registrationId, MockHttpSession session) throws Exception {
		String body = mockMvc.perform(link(registrationId, session))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		Matcher matcher = AUTHORIZATION_URI_PATTERN.matcher(body);
		assertTrue(matcher.find(), "authorizationUri가 없습니다: " + body);
		return matcher.group(1);
	}

	private String authorizationRedirect(String authorizationUri, MockHttpSession session) throws Exception {
		String location = mockMvc.perform(get(authorizationUri).session(session))
			.andExpect(status().is3xxRedirection())
			.andReturn()
			.getResponse()
			.getHeader("Location");
		assertTrue(
			location != null && location.startsWith("https://nid.naver.com/"),
			"연결 시작이 제공자로 이동하지 않았습니다: " + location);
		return location;
	}

	private MvcResult callback(String registrationId, MockHttpSession session, String state) throws Exception {
		return mockMvc.perform(
			get("/api/auth/social/callback/" + registrationId).param("code", "stub-code")
				.param("state", state)
				.session(session))
			.andReturn();
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

	private MockHttpServletRequestBuilder link(String registrationId, MockHttpSession session) throws Exception {
		Cookie csrf = csrfCookie(session);
		return post(LINK_URI, registrationId).session(session)
			.cookie(csrf)
			.header("X-XSRF-TOKEN", csrf.getValue());
	}

	private Cookie csrfCookie(MockHttpSession session) throws Exception {
		Cookie csrf = mockMvc.perform(get("/api/auth/csrf").session(session))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getCookie("XSRF-TOKEN");
		assertNotNull(csrf, "CSRF 토큰 쿠키가 없습니다");
		return csrf;
	}

	private UserAccount createAccount() {
		return userAccountService.createAccount(
			new CreateUserAccountCommand(
				UserEmail.from("link-" + UUID.randomUUID() + "@example.com").orElseThrow(),
				RawPassword.from("123456789012345").orElseThrow(),
				UserNickname.from("밤톨").orElseThrow()));
	}

	private MockHttpSession signedInSession() {
		return signedInSession(createAccount());
	}

	private MockHttpSession signedInSession(UserAccount account) {
		MockHttpSession session = new MockHttpSession();
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(
			UsernamePasswordAuthenticationToken.authenticated(
				new CurrentUserPrincipal(account.id()), null, AuthorityUtils.NO_AUTHORITIES));
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
		return session;
	}
}
