package cloud.bamsongi.albammate.auth.controller;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import cloud.bamsongi.albammate.auth.service.LoginService;
import cloud.bamsongi.albammate.global.config.SecurityConfig;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;
import cloud.bamsongi.albammate.global.security.error.ApiAccessDeniedHandler;
import cloud.bamsongi.albammate.global.security.error.ApiAuthenticationEntryPoint;
import cloud.bamsongi.albammate.global.security.error.SecurityErrorResponseWriter;
import jakarta.servlet.http.Cookie;

@WebMvcTest(controllers = {LoginController.class, CsrfController.class})
@Import({
	SecurityConfig.class,
	ApiAccessDeniedHandler.class,
	ApiAuthenticationEntryPoint.class,
	SecurityErrorResponseWriter.class,
	GlobalExceptionHandler.class,
	LoginControllerTest.TestBeans.class
})
class LoginControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private LoginService loginService;

	@BeforeEach
	void resetMocks() {
		reset(loginService);
	}

	@Test
	void DTO_검증에_실패하면_로그인_서비스를_호출하지_않고_VALIDATION_ERROR를_반환한다() throws Exception {
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

	@TestConfiguration(proxyBeanMethods = false)
	static class TestBeans {

		@Bean
		LoginService loginService() {
			return org.mockito.Mockito.mock(LoginService.class);
		}
	}
}
