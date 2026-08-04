package cloud.bamsongi.albammate.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import cloud.bamsongi.albammate.global.security.ratelimit.AuthenticationRequestLimiter;
import cloud.bamsongi.albammate.global.security.ratelimit.LoginVerificationPermit;
import cloud.bamsongi.albammate.global.security.ratelimit.RateLimitDecision;
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

@SpringBootTest
@AutoConfigureMockMvc
@Import(SocialAccountLoginHttpIntegrationTest.LoginPathProbeConfiguration.class)
class SocialAccountLoginHttpIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SocialAccountService socialAccountService;

	@Autowired
	private UserAccountService userAccountService;

	@Autowired
	private LoginPathProbe loginPathProbe;

	@Test
	void 비밀번호_없는_소셜_사용자는_이메일이_있어도_로그인_HTTP에서_자격증명_미존재로_처리한다()
		throws Exception {
		String suffix = UUID.randomUUID().toString();
		String email = "social-only-" + suffix + "@example.com";
		socialAccountService.login(
			new SocialIdentity(
				SocialProvider.GOOGLE,
				"subject-" + suffix,
				Optional.of(UserEmail.from(email).orElseThrow()),
				Optional.of(UserNickname.from("소셜 전용 사용자").orElseThrow())));
		assertTrue(userAccountService.findCredentialsByEmail(UserEmail.from(email).orElseThrow()).isEmpty());

		loginPathProbe.reset();

		mockMvc.perform(login(csrfToken(), email, "198.51.100.210"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.status").value(401))
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
			.andExpect(jsonPath("$.data").value((Object)null));

		assertEquals(1, loginPathProbe.passwordMatchCount());
		assertTrue(loginPathProbe.lastPasswordHash().startsWith("{bcrypt}"));
		assertEquals(1, loginPathProbe.loginRequestCount());
		assertEquals(1, loginPathProbe.loginVerificationAcquireCount());
		assertEquals(1, loginPathProbe.loginFailureCheckCount());
		assertEquals(1, loginPathProbe.loginFailureRecordCount());
	}

	@Test
	void 이메일_계정은_정상_비밀번호로_로그인하고_오류_비밀번호는_소셜_계정과_동일하게_거부한다() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String email = "email-login-" + suffix + "@example.com";
		UserAccount account = userAccountService.createAccount(new CreateUserAccountCommand(
			email(email), RawPassword.from("123456789012345").orElseThrow(), nickname("이메일 사용자")));

		loginPathProbe.reset();
		mockMvc.perform(login(csrfToken(), email, "198.51.100.211", "123456789012345"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.id").value(account.id()));

		assertEquals(1, loginPathProbe.passwordMatchCount());
		assertEquals(1, loginPathProbe.loginRequestCount());
		assertEquals(1, loginPathProbe.loginVerificationAcquireCount());
		assertEquals(1, loginPathProbe.loginFailureCheckCount());
		assertEquals(0, loginPathProbe.loginFailureRecordCount());

		loginPathProbe.reset();
		mockMvc.perform(login(csrfToken(), email, "198.51.100.212"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.status").value(401))
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
			.andExpect(jsonPath("$.data").value((Object)null));

		assertEquals(1, loginPathProbe.passwordMatchCount());
		assertEquals(1, loginPathProbe.loginFailureRecordCount());
	}

	private Cookie csrfToken() throws Exception {
		Cookie csrfToken = mockMvc.perform(get("/api/auth/csrf"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getCookie("XSRF-TOKEN");
		assertNotNull(csrfToken);
		return csrfToken;
	}

	private MockHttpServletRequestBuilder login(Cookie csrfToken, String email, String remoteIp) {
		return login(csrfToken, email, remoteIp, "wrong-password");
	}

	private MockHttpServletRequestBuilder login(Cookie csrfToken, String email, String remoteIp, String password) {
		return post("/api/auth/login")
			.cookie(csrfToken)
			.header("X-XSRF-TOKEN", csrfToken.getValue())
			.with(
				request -> {
					request.setRemoteAddr(remoteIp);
					return request;
				})
			.contentType("application/json")
			.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
	}

	private UserEmail email(String value) {
		return UserEmail.from(value).orElseThrow();
	}

	private UserNickname nickname(String value) {
		return UserNickname.from(value).orElseThrow();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class LoginPathProbeConfiguration {

		@Bean
		LoginPathProbe loginPathProbe() {
			return new LoginPathProbe();
		}

		@Bean
		@Primary
		AuthenticationRequestLimiter recordingAuthenticationRequestLimiter(
			@Qualifier("inMemoryAuthenticationRequestLimiter") AuthenticationRequestLimiter delegate,
			LoginPathProbe probe) {
			return new RecordingAuthenticationRequestLimiter(delegate, probe);
		}

		@Bean
		@Primary
		PasswordEncoder recordingPasswordEncoder(
			@Qualifier("passwordEncoder") PasswordEncoder delegate, LoginPathProbe probe) {
			return new RecordingPasswordEncoder(delegate, probe);
		}
	}

	static final class LoginPathProbe {

		private int passwordMatchCount;
		private String lastPasswordHash;
		private int loginRequestCount;
		private int loginVerificationAcquireCount;
		private int loginFailureCheckCount;
		private int loginFailureRecordCount;

		synchronized void reset() {
			passwordMatchCount = 0;
			lastPasswordHash = null;
			loginRequestCount = 0;
			loginVerificationAcquireCount = 0;
			loginFailureCheckCount = 0;
			loginFailureRecordCount = 0;
		}

		synchronized void recordPasswordMatch(String passwordHash) {
			passwordMatchCount++;
			lastPasswordHash = passwordHash;
		}

		synchronized void recordLoginRequest() {
			loginRequestCount++;
		}

		synchronized void recordLoginVerificationAcquire() {
			loginVerificationAcquireCount++;
		}

		synchronized void recordLoginFailureCheck() {
			loginFailureCheckCount++;
		}

		synchronized void recordLoginFailure() {
			loginFailureRecordCount++;
		}

		synchronized int passwordMatchCount() {
			return passwordMatchCount;
		}

		synchronized String lastPasswordHash() {
			assertNotNull(lastPasswordHash);
			return lastPasswordHash;
		}

		synchronized int loginRequestCount() {
			return loginRequestCount;
		}

		synchronized int loginVerificationAcquireCount() {
			return loginVerificationAcquireCount;
		}

		synchronized int loginFailureCheckCount() {
			return loginFailureCheckCount;
		}

		synchronized int loginFailureRecordCount() {
			return loginFailureRecordCount;
		}
	}

	static final class RecordingAuthenticationRequestLimiter implements AuthenticationRequestLimiter {

		private final AuthenticationRequestLimiter delegate;
		private final LoginPathProbe probe;

		RecordingAuthenticationRequestLimiter(AuthenticationRequestLimiter delegate, LoginPathProbe probe) {
			this.delegate = delegate;
			this.probe = probe;
		}

		@Override
		public RateLimitDecision checkAndRecordSignup(String remoteIp) {
			return delegate.checkAndRecordSignup(remoteIp);
		}

		@Override
		public RateLimitDecision checkAndRecordLogin(String remoteIp) {
			probe.recordLoginRequest();
			return delegate.checkAndRecordLogin(remoteIp);
		}

		@Override
		public RateLimitDecision checkLoginFailureAllowed(String normalizedEmail, String remoteIp) {
			probe.recordLoginFailureCheck();
			return delegate.checkLoginFailureAllowed(normalizedEmail, remoteIp);
		}

		@Override
		public RateLimitDecision recordLoginFailure(String normalizedEmail, String remoteIp) {
			probe.recordLoginFailure();
			return delegate.recordLoginFailure(normalizedEmail, remoteIp);
		}

		@Override
		public void resetLoginFailures(String normalizedEmail, String remoteIp) {
			delegate.resetLoginFailures(normalizedEmail, remoteIp);
		}

		@Override
		public Optional<LoginVerificationPermit> tryAcquireLoginVerification(String normalizedEmail, String remoteIp) {
			probe.recordLoginVerificationAcquire();
			return delegate.tryAcquireLoginVerification(normalizedEmail, remoteIp);
		}
	}

	static final class RecordingPasswordEncoder implements PasswordEncoder {

		private final PasswordEncoder delegate;
		private final LoginPathProbe probe;

		RecordingPasswordEncoder(PasswordEncoder delegate, LoginPathProbe probe) {
			this.delegate = delegate;
			this.probe = probe;
		}

		@Override
		public String encode(CharSequence rawPassword) {
			return delegate.encode(rawPassword);
		}

		@Override
		public boolean matches(CharSequence rawPassword, String encodedPassword) {
			probe.recordPasswordMatch(encodedPassword);
			return delegate.matches(rawPassword, encodedPassword);
		}

		@Override
		public boolean upgradeEncoding(String encodedPassword) {
			return delegate.upgradeEncoding(encodedPassword);
		}
	}
}
