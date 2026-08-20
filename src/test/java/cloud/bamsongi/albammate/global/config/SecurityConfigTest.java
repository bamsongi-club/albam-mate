package cloud.bamsongi.albammate.global.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.global.security.currentuser.SecurityContextCurrentUserAccessor;
import cloud.bamsongi.albammate.global.security.error.ApiAccessDeniedHandler;
import cloud.bamsongi.albammate.global.security.error.ApiAuthenticationEntryPoint;
import cloud.bamsongi.albammate.global.security.error.SecurityErrorResponseWriter;
import cloud.bamsongi.albammate.global.security.session.SessionCookieConfigurer;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = SecurityConfigTest.SecurityFixtureController.class)
@Import({
	SecurityConfig.class,
	ApiAccessDeniedHandler.class,
	ApiAuthenticationEntryPoint.class,
	SecurityErrorResponseWriter.class,
	GlobalExceptionHandler.class,
	SecurityConfigTest.SecurityFixtureController.class,
	SecurityConfigTest.SecurityFixtureConfiguration.class
})
class SecurityConfigTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private CsrfTokenRepository csrfTokenRepository;

	@Autowired
	private SecurityCookieProperties cookieProperties;

	@Autowired
	private ServletContextInitializer sessionCookieInitializer;

	@Autowired
	private SessionCookieConfigurer sessionCookieConfigurer;

	@Test
	void P0_공개_GET_경로와_선택_인증_방_경로를_허용한다() throws Exception {
		mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk());
		mockMvc.perform(get("/api/games")).andExpect(status().isOk());
		mockMvc.perform(get("/api/games/1")).andExpect(status().isOk());
		mockMvc.perform(get("/api/game-mechanisms")).andExpect(status().isOk());
		mockMvc.perform(get("/api/rooms")).andExpect(status().isOk());
	}

	@Test
	void 미매핑_API_경로는_인증보다_MVC_리소스_없음_오류를_우선한다() throws Exception {
		mockMvc.perform(get("/api/unknown"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.getCode()));
	}

	@Test
	void P0_엔드포인트의_지원하지_않는_메서드는_인증보다_MVC_메서드_오류를_우선한다() throws Exception {
		mockMvc.perform(post("/api/games"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(jsonPath("$.code").value(ErrorCode.METHOD_NOT_ALLOWED.getCode()));
		mockMvc.perform(post("/api/games/1"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(jsonPath("$.code").value(ErrorCode.METHOD_NOT_ALLOWED.getCode()));
		mockMvc.perform(post("/api/users/me"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(jsonPath("$.code").value(ErrorCode.METHOD_NOT_ALLOWED.getCode()));
		mockMvc.perform(put("/api/rooms/1"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(jsonPath("$.code").value(ErrorCode.METHOD_NOT_ALLOWED.getCode()));
	}

	@Test
	void 공개_경로는_미래_하위_경로까지_와일드카드로_노출하지_않는다() throws Exception {
		mockMvc.perform(get("/api/games/1/reviews"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
		mockMvc.perform(get("/api/rooms/1/participants/audit"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
	}

	@Test
	void 상세_경로의_잘못된_ID는_MVC에서_검증오류로_변환된다() throws Exception {
		for (String invalidId : java.util.List.of("admin", "-1", "1.5")) {
			mockMvc.perform(get("/api/games/" + invalidId))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		}
	}

	@Test
	void StrictHttpFirewall이_거절한_세미콜론_경로도_검증오류_공통_봉투로_반환한다() throws Exception {
		mockMvc.perform(get("/api/games;invalid"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(ErrorCode.VALIDATION_ERROR.getStatus()))
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()))
			.andExpect(jsonPath("$.message").value(ErrorCode.VALIDATION_ERROR.getMessage()))
			.andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
	}

	@Test
	void 기본_LogoutFilter는_비활성화되어_로그아웃_리다이렉트나_세션_무효화를_수행하지_않는다() throws Exception {
		MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
		MockHttpSession session = new MockHttpSession();
		session.setAttribute("marker", "present");

		mockMvc.perform(
			post("/logout")
				.session(session)
				.cookie(csrfCookie)
				.header("X-XSRF-TOKEN", csrfCookie.getValue())
				.with(authentication(authenticationFor(42L))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.getCode()));

		assertEquals("present", session.getAttribute("marker"));
		assertFalse(session.isInvalid());
	}

	@Test
	void context_path에서도_공개_로그인_경로의_CSFR_누락은_인증_필요가_아닌_토큰_오류다() throws Exception {
		mockMvc.perform(
			post("/app/api/auth/login")
				.contextPath("/app")
				.servletPath("/api/auth/login"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));
		mockMvc.perform(
			post("/app/api/auth/signup")
				.contextPath("/app")
				.servletPath("/api/auth/signup"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));
	}

	@Test
	void 회원가입과_로그인은_경로를_공개하지만_CSFR은_필수다() throws Exception {
		mockMvc.perform(post("/api/auth/signup"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));
		mockMvc.perform(post("/api/auth/login"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));
	}

	@Test
	void 보호_GET과_상태변경_경로는_인증을_요구한다() throws Exception {
		for (RequestBuilder request : java.util.List.of(
			post("/api/auth/logout"),
			get("/api/users/me"),
			patch("/api/users/me"),
			post("/api/rooms"),
			patch("/api/rooms/1"),
			post("/api/rooms/1/participants"),
			delete("/api/rooms/1/participants/me"),
			get("/api/users/me/rooms"))) {
			mockMvc.perform(request)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
		}
	}

	@Test
	void 보호_GET의_HEAD_요청도_인증을_요구한다() throws Exception {
		for (RequestBuilder request : java.util.List.of(head("/api/users/me"), head("/api/users/me/rooms"))) {
			mockMvc.perform(request)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
		}
	}

	@Test
	void 보호_상태변경_요청은_세션_없음이_CSFR_누락보다_우선한다() throws Exception {
		mockMvc.perform(patch("/api/rooms/1"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()))
			.andExpect(jsonPath("$.message").value(ErrorCode.UNAUTHENTICATED.getMessage()));
	}

	@Test
	void 인증된_상태변경_요청은_XSRF_쿠키와_헤더가_일치하면_통과한다() throws Exception {
		MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
		assertNotNull(csrfCookie);

		mockMvc.perform(
			post("/api/rooms")
				.cookie(csrfCookie)
				.header("X-XSRF-TOKEN", csrfCookie.getValue())
				.with(authentication(authenticationFor(42L))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.result").value("ok"));
	}

	@Test
	void 비로그인_CSFR_조회는_JSESSIONID를_발급하지_않고_운영_기본_Secure_XSRF_쿠키_속성을_맞춘다() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();

		Cookie xsrfCookie = result.getResponse().getCookie("XSRF-TOKEN");
		assertNotNull(xsrfCookie);
		assertNull(result.getResponse().getCookie("JSESSIONID"));
		assertNull(result.getRequest().getSession(false));
		assertEquals("/", xsrfCookie.getPath());
		assertTrue(xsrfCookie.isHttpOnly());
		assertTrue(xsrfCookie.getSecure());
		assertEquals("Lax", xsrfCookie.getAttribute("SameSite"));

		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		assertEquals("X-XSRF-TOKEN", body.get("data").get("headerName").asString());
		assertEquals(xsrfCookie.getValue(), body.get("data").get("token").asString());
	}

	@Test
	void 인증된_요청은_최소_주체에서_현재_사용자_ID만_얻는다() throws Exception {
		mockMvc.perform(get("/api/users/me").with(authentication(authenticationFor(42L))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.userId").value(42));
	}

	@Test
	void 보안_실패_응답에_세션_인증정보와_사용자_ID를_담지_않는다() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/users/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andReturn();
		String responseBody = result.getResponse().getContentAsString();
		assertTrue(responseBody.contains(ErrorCode.UNAUTHENTICATED.getCode()));
		assertFalse(responseBody.contains("JSESSIONID"));
		assertFalse(responseBody.contains("password"));
		assertFalse(responseBody.contains("credentials"));
		assertFalse(responseBody.contains("42"));
	}

	@Test
	void 운영_보안_쿠키_설정은_XSRF_쿠키를_Secure로_만든다() {
		boolean previousSecure = cookieProperties.isSecure();
		cookieProperties.setSecure(true);
		try {
			CsrfToken token = csrfTokenRepository.generateToken(new MockHttpServletRequest());
			MockHttpServletRequest request = new MockHttpServletRequest();
			MockHttpServletResponse response = new MockHttpServletResponse();

			csrfTokenRepository.saveToken(token, request, response);

			Cookie cookie = response.getCookie("XSRF-TOKEN");
			assertNotNull(cookie);
			assertTrue(cookie.getSecure());
			assertTrue(cookie.isHttpOnly());
			assertEquals("/", cookie.getPath());
			assertEquals("Lax", cookie.getAttribute("SameSite"));
		} finally {
			cookieProperties.setSecure(previousSecure);
		}
	}

	@Test
	void JSESSIONID는_초기화기에서_쿠키_속성을_제공한다() throws Exception {
		MockServletContext servletContext = new MockServletContext();

		sessionCookieInitializer.onStartup(servletContext);

		SessionCookieConfig cookieConfig = servletContext.getSessionCookieConfig();
		assertEquals("JSESSIONID", cookieConfig.getName());
		assertEquals("/", cookieConfig.getPath());
		assertTrue(cookieConfig.isHttpOnly());
		assertTrue(cookieConfig.isSecure());
		assertEquals("Lax", cookieConfig.getAttribute("SameSite"));
	}

	@Test
	void 로컬_HTTP_설정은_XSRF_TOKEN과_JSESSIONID의_Secure를_모두_끄고_나머지_계약을_유지한다() throws Exception {
		boolean previousSecure = cookieProperties.isSecure();
		cookieProperties.setSecure(false);
		try {
			CsrfToken token = csrfTokenRepository.generateToken(new MockHttpServletRequest());
			MockHttpServletResponse response = new MockHttpServletResponse();
			csrfTokenRepository.saveToken(token, new MockHttpServletRequest(), response);

			Cookie xsrfCookie = response.getCookie("XSRF-TOKEN");
			Cookie sessionCookie = sessionCookieConfigurer.sessionCookie("session-id");
			MockServletContext servletContext = new MockServletContext();
			sessionCookieInitializer.onStartup(servletContext);

			assertNotNull(xsrfCookie);
			assertFalse(xsrfCookie.getSecure());
			assertEquals("/", xsrfCookie.getPath());
			assertTrue(xsrfCookie.isHttpOnly());
			assertEquals("Lax", xsrfCookie.getAttribute("SameSite"));
			assertFalse(sessionCookie.getSecure());
			assertEquals("/", sessionCookie.getPath());
			assertTrue(sessionCookie.isHttpOnly());
			assertEquals("Lax", sessionCookie.getAttribute("SameSite"));
			assertFalse(servletContext.getSessionCookieConfig().isSecure());
		} finally {
			cookieProperties.setSecure(previousSecure);
		}
	}

	private UsernamePasswordAuthenticationToken authenticationFor(long userId) {
		return new UsernamePasswordAuthenticationToken(
			new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES);
	}

	@RestController
	public static class SecurityFixtureController {

		private final CurrentUserAccessor currentUserAccessor;

		SecurityFixtureController(CurrentUserAccessor currentUserAccessor) {
			this.currentUserAccessor = currentUserAccessor;
		}

		@GetMapping({"/api/auth/csrf", "/api/games", "/api/game-mechanisms", "/api/rooms"})
		MapResponse publicResponse(HttpServletRequest request) {
			if ("/api/auth/csrf".equals(request.getRequestURI())) {
				CsrfToken token = (CsrfToken)request.getAttribute(CsrfToken.class.getName());
				return MapResponse.csrf(token);
			}
			return MapResponse.ok();
		}

		@GetMapping("/api/games/{gameId}")
		MapResponse gameDetail(@PathVariable @Positive Long gameId) {
			return MapResponse.ok();
		}

		@PostMapping({"/api/auth/signup", "/api/auth/login"})
		MapResponse publicPost() {
			return MapResponse.ok();
		}

		@GetMapping("/api/users/me")
		MapResponse currentUser() {
			return MapResponse.user(currentUserAccessor.requireCurrentUserId());
		}

		@PostMapping("/api/rooms")
		MapResponse protectedPost() {
			return MapResponse.ok();
		}

		@PatchMapping("/api/rooms/{roomId}")
		MapResponse protectedPatch(@PathVariable @Positive Long roomId) {
			return MapResponse.ok();
		}
	}

	record MapResponse(int status, Object data) {

		static MapResponse ok() {
			return new MapResponse(200, java.util.Map.of("result", "ok"));
		}

		static MapResponse user(long userId) {
			return new MapResponse(200, java.util.Map.of("userId", userId));
		}

		static MapResponse csrf(CsrfToken token) {
			return new MapResponse(
				200,
				java.util.Map.of(
					"headerName", token.getHeaderName(),
					"token", token.getToken()));
		}
	}

	@TestConfiguration
	static class SecurityFixtureConfiguration {

		@Bean
		CurrentUserAccessor currentUserAccessor() {
			return new SecurityContextCurrentUserAccessor();
		}
	}
}
