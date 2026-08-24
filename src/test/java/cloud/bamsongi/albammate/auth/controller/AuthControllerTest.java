package cloud.bamsongi.albammate.auth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import cloud.bamsongi.albammate.auth.security.AppSessionEstablisher;
import cloud.bamsongi.albammate.auth.service.LoginService;
import cloud.bamsongi.albammate.auth.service.SignupService;
import cloud.bamsongi.albammate.global.config.SecurityConfig;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;
import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;
import cloud.bamsongi.albammate.global.security.error.ApiAccessDeniedHandler;
import cloud.bamsongi.albammate.global.security.error.ApiAuthenticationEntryPoint;
import cloud.bamsongi.albammate.global.security.error.SecurityErrorResponseWriter;
import cloud.bamsongi.albammate.global.security.ratelimit.AuthenticationRequestLimiter;
import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.RawPassword;
import cloud.bamsongi.albammate.user.contract.UserAccount;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = AuthController.class)
@Import({
	SecurityConfig.class,
	AppSessionEstablisher.class,
	ApiAccessDeniedHandler.class,
	ApiAuthenticationEntryPoint.class,
	SecurityErrorResponseWriter.class,
	GlobalExceptionHandler.class,
	AuthControllerTest.TestBeans.class
})
class AuthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private AuthenticationRequestLimiter requestLimiter;

	@Autowired
	private UserAccountService userAccountService;

	@Autowired
	private LoginService loginService;

	@BeforeEach
	void resetMocks() {
		reset(requestLimiter, userAccountService, loginService);
	}

	@Test
	void 비로그인_조회는_세션을_만들지_않고_운영_기본_Secure_XSRF_쿠키와_응답_토큰을_일치시킨다() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/auth/csrf"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.headerName").value("X-XSRF-TOKEN"))
			.andReturn();

		Cookie xsrfCookie = result.getResponse().getCookie("XSRF-TOKEN");
		assertNotNull(xsrfCookie);
		assertNull(result.getResponse().getCookie("JSESSIONID"));
		assertNull(result.getRequest().getSession(false));
		assertEquals("/", xsrfCookie.getPath());
		assertTrue(xsrfCookie.isHttpOnly());
		assertTrue(xsrfCookie.getSecure());
		assertEquals("Lax", xsrfCookie.getAttribute("SameSite"));

		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		assertEquals(xsrfCookie.getValue(), body.get("data").get("token").asString());
	}

	@Test
	void 유효하지_않은_CSRF_토큰으로_공개_상태변경을_시도하면_거절한다() throws Exception {
		MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie xsrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
		assertNotNull(xsrfCookie);

		mockMvc.perform(
			post("/api/auth/login")
				.cookie(xsrfCookie)
				.header("X-XSRF-TOKEN", "invalid-token"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"))
			.andExpect(jsonPath("$.data").value((Object)null));
	}

	@Test
	void CSRF가_있는_회원가입은_201과_UserSummary를_반환하고_세션을_만들지_않는다() throws Exception {
		when(userAccountService.createAccount(
			command("user@example.com", "123456789012345", "닉네임")))
			.thenReturn(new UserAccount(7L, "닉네임"));
		MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");

		mockMvc.perform(
			post("/api/auth/signup")
				.cookie(csrfCookie)
				.header("X-XSRF-TOKEN", csrfCookie.getValue())
				.with(remoteAddress("198.51.100.31"))
				.contentType("application/json")
				.content(
					"{\"email\":\" User@Example.com \","
						+ "\"password\":\"123456789012345\","
						+ "\"nickname\":\" 닉네임 \"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value(201))
			.andExpect(jsonPath("$.data.id").value(7))
			.andExpect(jsonPath("$.data.nickname").value("닉네임"))
			.andExpect(jsonPath("$.data.profileImageUrl").value((Object)null))
			.andExpect(jsonPath("$.data.email").doesNotExist())
			.andExpect(jsonPath("$.data.password").doesNotExist())
			.andExpect(header().doesNotExist("Set-Cookie"));

		org.mockito.Mockito.verify(requestLimiter).requireSignupAllowed("198.51.100.31");
	}

	@Test
	void 회원가입_DTO_검증에_실패하면_요청제한과_계정생성을_소모하지_않는다() throws Exception {
		MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");

		mockMvc.perform(
			post("/api/auth/signup")
				.cookie(csrfCookie)
				.header("X-XSRF-TOKEN", csrfCookie.getValue())
				.contentType("application/json")
				.content(
					"{\"email\":\"not-an-email\","
						+ "\"password\":\"123456789012345\","
						+ "\"nickname\":\"닉네임\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(requestLimiter, userAccountService);
	}

	@Test
	void 열네_code_point_회원가입_비밀번호는_해시와_사용자_생성_전에_VALIDATION_ERROR로_거절한다() throws Exception {
		MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");

		mockMvc.perform(
			post("/api/auth/signup")
				.cookie(csrfCookie)
				.header("X-XSRF-TOKEN", csrfCookie.getValue())
				.contentType("application/json")
				.content(
					"{\"email\":\"user@example.com\","
						+ "\"password\":\"12345678901234\","
						+ "\"nickname\":\"닉네임\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(requestLimiter, userAccountService);
	}

	@Test
	void UTF8_72바이트_한글_24자는_가입하고_73바이트는_VALIDATION_ERROR로_거절한다() throws Exception {
		String allowedPassword = "가".repeat(24);
		String rejectedPassword = allowedPassword + "a";
		when(userAccountService.createAccount(command("unicode@example.com", allowedPassword, "닉네임")))
			.thenReturn(new UserAccount(8L, "닉네임"));
		MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");

		mockMvc.perform(
			post("/api/auth/signup")
				.cookie(csrfCookie)
				.header("X-XSRF-TOKEN", csrfCookie.getValue())
				.with(remoteAddress("198.51.100.34"))
				.contentType("application/json")
				.content(
					"{\"email\":\"unicode@example.com\","
						+ "\"password\":\"" + allowedPassword + "\","
						+ "\"nickname\":\"닉네임\"}"))
			.andExpect(status().isCreated());

		reset(requestLimiter, userAccountService);
		mockMvc.perform(
			post("/api/auth/signup")
				.cookie(csrfCookie)
				.header("X-XSRF-TOKEN", csrfCookie.getValue())
				.contentType("application/json")
				.content(
					"{\"email\":\"too-long@example.com\","
						+ "\"password\":\"" + rejectedPassword + "\","
						+ "\"nickname\":\"닉네임\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(requestLimiter, userAccountService);
	}

	@Test
	void 예순네_ASCII_회원가입_비밀번호는_허용하고_예순다섯자는_서비스_호출_없이_VALIDATION_ERROR로_거절한다() throws Exception {
		String allowedPassword = "a".repeat(64);
		when(userAccountService.createAccount(command("ascii@example.com", allowedPassword, "닉네임")))
			.thenReturn(new UserAccount(9L, "닉네임"));
		MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");

		mockMvc.perform(
			post("/api/auth/signup")
				.cookie(csrfCookie)
				.header("X-XSRF-TOKEN", csrfCookie.getValue())
				.with(remoteAddress("198.51.100.35"))
				.contentType("application/json")
				.content(
					"{\"email\":\"ascii@example.com\","
						+ "\"password\":\"" + allowedPassword + "\","
						+ "\"nickname\":\"닉네임\"}"))
			.andExpect(status().isCreated());

		reset(requestLimiter, userAccountService);
		mockMvc.perform(
			post("/api/auth/signup")
				.cookie(csrfCookie)
				.header("X-XSRF-TOKEN", csrfCookie.getValue())
				.contentType("application/json")
				.content(
					"{\"email\":\"too-long-ascii@example.com\","
						+ "\"password\":\"" + "a".repeat(65) + "\","
						+ "\"nickname\":\"닉네임\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(requestLimiter, userAccountService);
	}

	@Test
	void 회원가입_요청제한을_초과하면_사용자_생성_없이_429와_Retry_After를_반환한다() throws Exception {
		doThrow(new RateLimitExceededException(12))
			.when(requestLimiter)
			.requireSignupAllowed("198.51.100.32");
		MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");

		mockMvc.perform(
			post("/api/auth/signup")
				.cookie(csrfCookie)
				.header("X-XSRF-TOKEN", csrfCookie.getValue())
				.with(remoteAddress("198.51.100.32"))
				.contentType("application/json")
				.content(
					"{\"email\":\"user@example.com\","
						+ "\"password\":\"123456789012345\","
						+ "\"nickname\":\"닉네임\"}"))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().string("Retry-After", "12"))
			.andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));

		verifyNoInteractions(userAccountService);
	}

	@Test
	void T6_인증_제한_Redis를_확인할_수_없으면_회원가입과_로그인은_503이고_Retry_After가_없다() throws Exception {
		doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE))
			.when(requestLimiter)
			.requireSignupAllowed("198.51.100.35");
		doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE))
			.when(loginService)
			.login(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("198.51.100.35"));
		MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");

		mockMvc.perform(
			post("/api/auth/signup")
				.cookie(csrfCookie)
				.header("X-XSRF-TOKEN", csrfCookie.getValue())
				.with(remoteAddress("198.51.100.35"))
				.contentType("application/json")
				.content(
					"{\"email\":\"unavailable@example.com\",\"password\":\"123456789012345\",\"nickname\":\"닉네임\"}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(header().doesNotExist("Retry-After"))
			.andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));

		mockMvc.perform(
			post("/api/auth/login")
				.cookie(csrfCookie)
				.header("X-XSRF-TOKEN", csrfCookie.getValue())
				.with(remoteAddress("198.51.100.35"))
				.contentType("application/json")
				.content("{\"email\":\"unavailable@example.com\",\"password\":\"123456789012345\"}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(header().doesNotExist("Retry-After"))
			.andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
	}

	@Test
	void 회원가입_CSRF가_없으면_컨트롤러까지_도달하지_않는다() throws Exception {
		mockMvc.perform(
			post("/api/auth/signup")
				.contentType("application/json")
				.content(
					"{\"email\":\"user@example.com\","
						+ "\"password\":\"123456789012345\","
						+ "\"nickname\":\"닉네임\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

		verifyNoInteractions(requestLimiter, userAccountService);
	}

	@Test
	void 로그인_DTO_검증에_실패하면_로그인_서비스를_호출하지_않고_VALIDATION_ERROR를_반환한다() throws Exception {
		MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");

		mockMvc.perform(
			post("/api/auth/login")
				.cookie(csrfCookie)
				.header("X-XSRF-TOKEN", csrfCookie.getValue())
				.contentType("application/json")
				.content("{\"email\":\"not-an-email\",\"password\":\"password\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(loginService);
	}

	private RequestPostProcessor remoteAddress(String remoteAddress) {
		return request -> {
			request.setRemoteAddr(remoteAddress);
			return request;
		};
	}

	private CreateUserAccountCommand command(String email, String password, String nickname) {
		return new CreateUserAccountCommand(
			UserEmail.from(email).orElseThrow(),
			RawPassword.from(password).orElseThrow(),
			UserNickname.from(nickname).orElseThrow());
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestBeans {

		@Bean
		AuthenticationRequestLimiter requestLimiter() {
			return org.mockito.Mockito.mock(AuthenticationRequestLimiter.class);
		}

		@Bean
		UserAccountService userAccountService() {
			return org.mockito.Mockito.mock(UserAccountService.class);
		}

		@Bean
		SignupService signupService(
			AuthenticationRequestLimiter requestLimiter,
			UserAccountService userAccountService) {
			return new SignupService(requestLimiter, userAccountService);
		}

		@Bean
		LoginService loginService() {
			return org.mockito.Mockito.mock(LoginService.class);
		}
	}
}
