package cloud.bamsongi.albammate.user.controller;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import cloud.bamsongi.albammate.auth.controller.AuthController;
import cloud.bamsongi.albammate.auth.security.AppSessionEstablisher;
import cloud.bamsongi.albammate.auth.service.LoginService;
import cloud.bamsongi.albammate.auth.service.SignupService;
import cloud.bamsongi.albammate.global.config.SecurityConfig;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.global.security.error.ApiAccessDeniedHandler;
import cloud.bamsongi.albammate.global.security.error.ApiAuthenticationEntryPoint;
import cloud.bamsongi.albammate.global.security.error.SecurityErrorResponseWriter;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import cloud.bamsongi.albammate.user.dto.UserProfileResponse;
import cloud.bamsongi.albammate.user.service.UserProfileService;
import jakarta.servlet.http.Cookie;

@WebMvcTest(controllers = {UserProfileController.class, AuthController.class})
@Import({
	SecurityConfig.class,
	AppSessionEstablisher.class,
	ApiAccessDeniedHandler.class,
	ApiAuthenticationEntryPoint.class,
	SecurityErrorResponseWriter.class,
	GlobalExceptionHandler.class,
	UserProfileControllerTest.TestBeans.class
})
class UserProfileControllerTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private UserProfileService userProfileService;

	@BeforeEach
	void resetMocks() {
		reset(userProfileService);
	}

	@Test
	void 인증_사용자는_자신의_UserProfileResponse만_조회한다() throws Exception {
		when(userProfileService.findProfile(7L)).thenReturn(new UserProfileResponse(7L, "닉네임", null));

		mockMvc.perform(get("/api/users/me").with(currentUserAuthentication()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.id").value(7))
			.andExpect(jsonPath("$.data.nickname").value("닉네임"))
			.andExpect(jsonPath("$.data.email").doesNotExist())
			.andExpect(jsonPath("$.data.passwordHash").doesNotExist());
	}

	@Test
	void 인증_사용자도_다른_사용자_프로필_조회_경로에는_접근할_수_없다() throws Exception {
		mockMvc.perform(get("/api/users/8").with(currentUserAuthentication()))
			.andExpect(status().isNotFound());

		verifyNoInteractions(userProfileService);
	}

	@Test
	void 유효한_CSRF와_닉네임으로_수정하면_UserProfileResponse를_반환한다() throws Exception {
		when(userProfileService.changeNickname(7L, UserNickname.from("새 닉네임").orElseThrow()))
			.thenReturn(new UserProfileResponse(7L, "새 닉네임", null));
		CsrfContext csrfContext = csrfContext();

		mockMvc.perform(
			patch("/api/users/me")
				.with(currentUserAuthentication())
				.session(csrfContext.session())
				.cookie(csrfContext.cookie())
				.header("X-XSRF-TOKEN", csrfContext.cookie().getValue())
				.contentType("application/json")
				.content("{\"nickname\":\" 새 닉네임 \"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.id").value(7))
			.andExpect(jsonPath("$.data.nickname").value("새 닉네임"));

		verify(userProfileService).changeNickname(7L, UserNickname.from("새 닉네임").orElseThrow());
	}

	@Test
	void 인증_사용자도_다른_사용자_프로필_수정_경로에는_접근할_수_없다() throws Exception {
		CsrfContext csrfContext = csrfContext();

		mockMvc.perform(
			patch("/api/users/8")
				.with(currentUserAuthentication())
				.session(csrfContext.session())
				.cookie(csrfContext.cookie())
				.header("X-XSRF-TOKEN", csrfContext.cookie().getValue())
				.contentType("application/json")
				.content("{\"nickname\":\"다른 사용자\"}"))
			.andExpect(status().isNotFound());

		verifyNoInteractions(userProfileService);
	}

	@Test
	void 비로그인_요청과_CSRF_오류는_컨트롤러까지_도달하지_않는다() throws Exception {
		mockMvc.perform(get("/api/users/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mockMvc.perform(
			patch("/api/users/me")
				.with(currentUserAuthentication())
				.contentType("application/json")
				.content("{\"nickname\":\"닉네임\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

		verifyNoInteractions(userProfileService);
	}

	@Test
	void 빈_객체와_제어문자_닉네임은_VALIDATION_ERROR다() throws Exception {
		CsrfContext csrfContext = csrfContext();

		mockMvc.perform(
			patch("/api/users/me")
				.with(currentUserAuthentication())
				.session(csrfContext.session())
				.cookie(csrfContext.cookie())
				.header("X-XSRF-TOKEN", csrfContext.cookie().getValue())
				.contentType("application/json")
				.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mockMvc.perform(
			patch("/api/users/me")
				.with(currentUserAuthentication())
				.session(csrfContext.session())
				.cookie(csrfContext.cookie())
				.header("X-XSRF-TOKEN", csrfContext.cookie().getValue())
				.contentType("application/json")
				.content("{\"nickname\":\"닉\\n네임\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(userProfileService);
	}

	private CsrfContext csrfContext() throws Exception {
		MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf").with(currentUserAuthentication()))
			.andExpect(status().isOk())
			.andReturn();
		return new CsrfContext(
			csrfResult.getResponse().getCookie("XSRF-TOKEN"),
			(MockHttpSession)csrfResult.getRequest().getSession(false));
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor currentUserAuthentication() {
		return authentication(
			UsernamePasswordAuthenticationToken.authenticated(
				new CurrentUserPrincipal(7L), null, AuthorityUtils.NO_AUTHORITIES));
	}

	private record CsrfContext(Cookie cookie, MockHttpSession session) {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestBeans {

		@Bean
		CurrentUserAccessor currentUserAccessor() {
			return () -> Optional.of(7L);
		}

		@Bean
		UserProfileService userProfileService() {
			return org.mockito.Mockito.mock(UserProfileService.class);
		}

		@Bean
		SignupService signupService() {
			return org.mockito.Mockito.mock(SignupService.class);
		}

		@Bean
		LoginService loginService() {
			return org.mockito.Mockito.mock(LoginService.class);
		}

		@Bean
		cloud.bamsongi.albammate.user.service.ProfileImageStorage profileImageStorage() {
			return org.mockito.Mockito.mock(cloud.bamsongi.albammate.user.service.ProfileImageStorage.class);
		}
	}
}
