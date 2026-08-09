package cloud.bamsongi.albammate.auth.controller;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;

@SpringBootTest
@AutoConfigureMockMvc
class SignupHttpIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Test
	void 회원가입_HTTP_경로는_계정만_생성하고_해시된_자격증명과_UserSummary를_반환한다() throws Exception {
		String email = "http-signup-success@example.com";
		String rawPassword = "Valid-Password123!";
		MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
		assertNotNull(csrfCookie);

		MvcResult signupResult = mockMvc.perform(
			post("/api/auth/signup")
				.cookie(csrfCookie)
				.header("X-XSRF-TOKEN", csrfCookie.getValue())
				.with(
					request -> {
						request.setRemoteAddr("198.51.100.99");
						return request;
					})
				.contentType("application/json")
				.content(
					"{\"email\":\" HTTP-Signup-Success@Example.com \","
						+ "\"password\":\"Valid-Password123!\","
						+ "\"nickname\":\" HTTP 사용자 \"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value(201))
			.andExpect(jsonPath("$.data.id").isNumber())
			.andExpect(jsonPath("$.data.nickname").value("HTTP 사용자"))
			.andExpect(jsonPath("$.data.email").doesNotExist())
			.andReturn();

		assertNull(signupResult.getResponse().getCookie("JSESSIONID"));
		User user = userRepository.findByEmail(email).orElseThrow();
		org.junit.jupiter.api.Assertions.assertTrue(user.getPasswordHash().startsWith("{bcrypt}"));
		org.junit.jupiter.api.Assertions.assertNotEquals(rawPassword, user.getPasswordHash());
	}

	@Test
	void 공백과_정규화하지_않은_Unicode_가입_비밀번호는_원문으로만_로그인된다() throws Exception {
		String email = "unicode-signup@example.com";
		String password = " e\u0301😀라마바사아자차카타파하 ";
		String normalizedPassword = " é😀라마바사아자차카타파하 ";
		MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
		assertNotNull(csrfCookie);

		mockMvc.perform(
			post("/api/auth/signup")
				.cookie(csrfCookie)
				.header("X-XSRF-TOKEN", csrfCookie.getValue())
				.contentType("application/json")
				.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"nickname\":\"원문 사용자\"}"))
			.andExpect(status().isCreated());

		MvcResult loginCsrf = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie loginCsrfCookie = loginCsrf.getResponse().getCookie("XSRF-TOKEN");
		assertNotNull(loginCsrfCookie);

		mockMvc.perform(
			post("/api/auth/login")
				.cookie(loginCsrfCookie)
				.header("X-XSRF-TOKEN", loginCsrfCookie.getValue())
				.contentType("application/json")
				.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
			.andExpect(status().isOk());

		MvcResult normalizedLoginCsrf = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie normalizedLoginCsrfCookie = normalizedLoginCsrf.getResponse().getCookie("XSRF-TOKEN");
		assertNotNull(normalizedLoginCsrfCookie);

		mockMvc.perform(
			post("/api/auth/login")
				.cookie(normalizedLoginCsrfCookie)
				.header("X-XSRF-TOKEN", normalizedLoginCsrfCookie.getValue())
				.contentType("application/json")
				.content("{\"email\":\"" + email + "\",\"password\":\"" + normalizedPassword + "\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
	}
}
