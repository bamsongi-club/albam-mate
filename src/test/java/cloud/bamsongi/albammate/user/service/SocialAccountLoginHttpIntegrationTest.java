package cloud.bamsongi.albammate.user.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import cloud.bamsongi.albammate.user.contract.SocialAccountService;
import cloud.bamsongi.albammate.user.contract.SocialIdentity;
import cloud.bamsongi.albammate.user.contract.SocialProvider;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import jakarta.servlet.http.Cookie;

@SpringBootTest
@AutoConfigureMockMvc
class SocialAccountLoginHttpIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SocialAccountService socialAccountService;

	@Autowired
	private UserAccountService userAccountService;

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

		Cookie csrfToken = mockMvc.perform(get("/api/auth/csrf"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getCookie("XSRF-TOKEN");
		org.junit.jupiter.api.Assertions.assertNotNull(csrfToken);

		mockMvc.perform(login(csrfToken, email, "198.51.100.210"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.status").value(401))
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
			.andExpect(jsonPath("$.data").value((Object)null));
	}

	private MockHttpServletRequestBuilder login(Cookie csrfToken, String email, String remoteIp) {
		return post("/api/auth/login")
			.cookie(csrfToken)
			.header("X-XSRF-TOKEN", csrfToken.getValue())
			.with(
				request -> {
					request.setRemoteAddr(remoteIp);
					return request;
				})
			.contentType("application/json")
			.content("{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}");
	}
}
