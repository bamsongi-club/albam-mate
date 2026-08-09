package cloud.bamsongi.albammate.auth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import cloud.bamsongi.albammate.auth.security.InvalidatingCsrfTokenRepository;
import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.RawPassword;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;

@SpringBootTest
@AutoConfigureMockMvc
class LoginLogoutHttpIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserAccountService userAccountService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void 기존_8자_비밀번호_계정은_가입_정책_변경_뒤에도_로그인한다() throws Exception {
		String email = "legacy-password@example.com";
		String password = "legacy8!";
		assertTrue(RawPassword.from(password).isEmpty());
		userRepository.saveAndFlush(User.create(email, passwordEncoder.encode(password), "기존 사용자"));
		MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
		assertNotNull(csrfCookie);

		mockMvc.perform(
			post("/api/auth/login")
				.cookie(csrfCookie)
				.header("X-XSRF-TOKEN", csrfCookie.getValue())
				.contentType("application/json")
				.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.nickname").value("기존 사용자"));
	}

	@Test
	void 로그인_성공은_사용자_요약을_반환한다() throws Exception {
		String email = "login-logout-http@example.com";
		String password = "123456789012345";
		var account = userAccountService.createAccount(command(email, password, "로그인 사용자"));
		MockHttpSession session = new MockHttpSession();

		MvcResult beforeLoginCsrf = mockMvc.perform(get("/api/auth/csrf").session(session))
			.andExpect(status().isOk())
			.andReturn();
		Cookie oldCsrf = beforeLoginCsrf.getResponse().getCookie("XSRF-TOKEN");
		assertNotNull(oldCsrf);

		MvcResult login = mockMvc.perform(
			post("/api/auth/login")
				.cookie(oldCsrf)
				.header("X-XSRF-TOKEN", oldCsrf.getValue())
				.session(session)
				.contentType("application/json")
				.content(
					"{\"email\":\" LOGIN-LOGOUT-HTTP@Example.com \","
						+ "\"password\":\"123456789012345\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.id").value(account.id()))
			.andExpect(jsonPath("$.data.nickname").value("로그인 사용자"))
			.andExpect(jsonPath("$.data.profileImageUrl").value((Object)null))
			.andExpect(jsonPath("$.data.email").doesNotExist())
			.andReturn();

		// MockMvc는 컨테이너의 세션 쿠키·ID 교체를 재현하지 않는다. 실제 HTTP 로그인·로그아웃은 별도 테스트에서 검증한다.
		assertNotNull(login.getRequest().getSession(false));
	}

	@Test
	void 이미지가_있는_기존_사용자_로그인은_프로필_URL을_반환한다() throws Exception {
		String email = "login-profile-image@example.com";
		var account = userAccountService.createAccount(command(email, "123456789012345", "이미지 사용자"));
		User user = userRepository.findById(account.id()).orElseThrow();
		user.changeProfileImageUrl("/uploads/profile/login-image.png");
		userRepository.saveAndFlush(user);
		MockHttpSession session = new MockHttpSession();

		MvcResult beforeLoginCsrf = mockMvc.perform(get("/api/auth/csrf").session(session))
			.andExpect(status().isOk())
			.andReturn();
		Cookie csrfCookie = beforeLoginCsrf.getResponse().getCookie("XSRF-TOKEN");
		assertNotNull(csrfCookie);

		mockMvc.perform(
			post("/api/auth/login")
				.cookie(csrfCookie)
				.header("X-XSRF-TOKEN", csrfCookie.getValue())
				.session(session)
				.contentType("application/json")
				.content("{\"email\":\"" + email + "\",\"password\":\"123456789012345\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.profileImageUrl").value("/uploads/profile/login-image.png"));
	}

	@Test
	@ResourceLock(Resources.SYSTEM_PROPERTIES)
	void 세션_만료_시각은_UTC_지역시간이_아닌_epoch_millis_기준으로_다룬다() {
		MockHttpSession session = new MockHttpSession();
		session.setMaxInactiveInterval(90);

		long lastAccessedTime = session.getLastAccessedTime();
		long maxInactiveIntervalSeconds = session.getMaxInactiveInterval();
		TimeZone originalTimeZone = TimeZone.getDefault();

		try {
			TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
			Instant seoulExpiration = Instant.ofEpochMilli(lastAccessedTime).plusSeconds(maxInactiveIntervalSeconds);

			TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
			Instant losAngelesExpiration = Instant.ofEpochMilli(lastAccessedTime)
				.plusSeconds(maxInactiveIntervalSeconds);

			assertEquals(seoulExpiration, losAngelesExpiration);
			assertEquals(
				lastAccessedTime + maxInactiveIntervalSeconds * 1000,
				seoulExpiration.toEpochMilli());
		} finally {
			TimeZone.setDefault(originalTimeZone);
		}
	}

	@Test
	void 무효화된_세션은_로그아웃_경로와_별도로_보호_API에서_401을_반환한다() throws Exception {
		MockHttpSession invalidatedSession = new MockHttpSession();
		String invalidatedSessionId = invalidatedSession.getId();
		invalidatedSession.invalidate();

		mockMvc.perform(get("/api/users/me").cookie(new Cookie("JSESSIONID", invalidatedSessionId)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void 세션없는_CSRF_무효화는_세션을_생성하지_않는다() {
		CsrfTokenRepository repository = new InvalidatingCsrfTokenRepository(new CookieCsrfTokenRepository());
		MockHttpServletRequest request = new MockHttpServletRequest();

		repository.saveToken(null, request, new MockHttpServletResponse());

		assertNull(request.getSession(false));
	}

	@Test
	void 잘못된_로그인_자격증명은_401_오류_봉투를_반환하고_실패_5회_뒤_요청을_제한한다() throws Exception {
		String email = "invalid-login-contract@example.com";
		String remoteIp = "198.51.100.111";
		Cookie csrfCookie = mockMvc.perform(get("/api/auth/csrf"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getCookie("XSRF-TOKEN");
		assertNotNull(csrfCookie);

		for (int attempt = 1; attempt <= 5; attempt++) {
			var result = mockMvc.perform(wrongLogin(csrfCookie, email, remoteIp));
			result.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
				.andExpect(jsonPath("$.message").value("이메일 또는 비밀번호가 일치하지 않습니다."))
				.andExpect(jsonPath("$.data").value((Object)null));
		}

		mockMvc.perform(wrongLogin(csrfCookie, email, remoteIp))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.status").value(429))
			.andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
			.andExpect(jsonPath("$.data").value((Object)null))
			.andExpect(
				result -> {
					String retryAfter = result.getResponse().getHeader("Retry-After");
					assertNotNull(retryAfter);
					assertTrue(Integer.parseInt(retryAfter) > 0);
				});
	}

	private MockHttpServletRequestBuilder wrongLogin(
		Cookie csrfCookie, String email, String remoteIp) {
		return post("/api/auth/login")
			.cookie(csrfCookie)
			.header("X-XSRF-TOKEN", csrfCookie.getValue())
			.with(
				request -> {
					request.setRemoteAddr(remoteIp);
					return request;
				})
			.contentType("application/json")
			.content("{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}");
	}

	private CreateUserAccountCommand command(String email, String password, String nickname) {
		return new CreateUserAccountCommand(
			UserEmail.from(email).orElseThrow(),
			RawPassword.from(password).orElseThrow(),
			UserNickname.from(nickname).orElseThrow());
	}
}
