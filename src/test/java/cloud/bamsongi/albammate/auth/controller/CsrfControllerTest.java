package cloud.bamsongi.albammate.auth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import cloud.bamsongi.albammate.global.config.SecurityConfig;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;
import cloud.bamsongi.albammate.global.security.error.ApiAccessDeniedHandler;
import cloud.bamsongi.albammate.global.security.error.ApiAuthenticationEntryPoint;
import cloud.bamsongi.albammate.global.security.error.SecurityErrorResponseWriter;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = CsrfController.class)
@Import({
	SecurityConfig.class,
	ApiAccessDeniedHandler.class,
	ApiAuthenticationEntryPoint.class,
	SecurityErrorResponseWriter.class,
	GlobalExceptionHandler.class
})
class CsrfControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

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
}
