package cloud.bamsongi.albammate.auth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.RawPassword;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class LoginLogoutHttpIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private UserAccountService userAccountService;

    @Test
    void 로그인_성공은_세션을_교체하고_로그아웃은_세션과_CSRF를_무효화한다() throws Exception {
        String email = "login-logout-http@example.com";
        String password = "123456789012345";
        var account = userAccountService.createAccount(command(email, password, "로그인 사용자"));
        MvcResult anonymousCsrf =
                mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        Cookie anonymousToken = anonymousCsrf.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(anonymousToken);
        MockHttpSession oldSession = new MockHttpSession();
        String oldSessionId = oldSession.getId();

        MvcResult beforeLoginCsrf =
                mockMvc.perform(get("/api/auth/csrf").session(oldSession))
                        .andExpect(status().isOk())
                        .andReturn();
        Cookie oldCsrf = beforeLoginCsrf.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(oldCsrf);

        MvcResult login =
                mockMvc.perform(
                                post("/api/auth/login")
                                        .cookie(oldCsrf)
                                        .header("X-XSRF-TOKEN", oldCsrf.getValue())
                                        .session(oldSession)
                                        .contentType("application/json")
                                        .content(
                                                "{\"email\":\" LOGIN-LOGOUT-HTTP@Example.com \","
                                                        + "\"password\":\"123456789012345\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.id").value(account.id()))
                        .andExpect(jsonPath("$.data.nickname").value("로그인 사용자"))
                        .andExpect(jsonPath("$.data.email").doesNotExist())
                        .andReturn();

        Cookie sessionCookie = login.getResponse().getCookie("JSESSIONID");
        assertNotNull(sessionCookie);
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertNotNull(session);
        String newSessionId = session.getId();
        assertNotEquals(oldSessionId, newSessionId);

        mockMvc.perform(
                        post("/api/auth/login")
                                .cookie(oldCsrf)
                                .header("X-XSRF-TOKEN", oldCsrf.getValue())
                                .session(session)
                                .contentType("application/json")
                                .content(
                                        "{\"email\":\" LOGIN-LOGOUT-HTTP@Example.com \","
                                                + "\"password\":\"123456789012345\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

        mockMvc.perform(
                        post("/api/auth/signup")
                                .cookie(anonymousToken)
                                .header("X-XSRF-TOKEN", anonymousToken.getValue())
                                .with(
                                        request -> {
                                            request.setRemoteAddr("198.51.100.101");
                                            return request;
                                        })
                                .contentType("application/json")
                                .content(
                                        "{\"email\":\"anonymous-client-b@example.com\","
                                                + "\"password\":\"123456789012346\","
                                                + "\"nickname\":\"익명 클라이언트 B\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.nickname").value("익명 클라이언트 B"));

        MvcResult afterLoginCsrf =
                mockMvc.perform(get("/api/auth/csrf").session(session))
                        .andExpect(status().isOk())
                        .andReturn();
        Cookie newCsrf = afterLoginCsrf.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(newCsrf);
        assertNotEquals(oldCsrf.getValue(), newCsrf.getValue());

        MvcResult logout =
                mockMvc.perform(
                                post("/api/auth/logout")
                                        .session(session)
                                        .cookie(newCsrf)
                                        .header("X-XSRF-TOKEN", newCsrf.getValue()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data").isEmpty())
                        .andReturn();

        assertTrue(session.isInvalid());
        assertNotNull(logout.getResponse().getCookie("JSESSIONID"));
        assertTrue(logout.getResponse().getCookie("JSESSIONID").getMaxAge() <= 0);

        mockMvc.perform(
                        post("/api/auth/login")
                                .cookie(newCsrf)
                                .header("X-XSRF-TOKEN", newCsrf.getValue())
                                .contentType("application/json")
                                .content(
                                        "{\"email\":\" LOGIN-LOGOUT-HTTP@Example.com \","
                                                + "\"password\":\"123456789012345\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

        mockMvc.perform(get("/api/users/me").cookie(sessionCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertNotEquals(oldSessionId, newSessionId);
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
            Instant seoulExpiration =
                    Instant.ofEpochMilli(lastAccessedTime).plusSeconds(maxInactiveIntervalSeconds);

            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            Instant losAngelesExpiration =
                    Instant.ofEpochMilli(lastAccessedTime).plusSeconds(maxInactiveIntervalSeconds);

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
    void 잘못된_로그인_자격증명은_401_오류_봉투를_반환하고_실패_5회_뒤_요청을_제한한다() throws Exception {
        String email = "invalid-login-contract@example.com";
        String remoteIp = "198.51.100.111";
        Cookie csrfCookie =
                mockMvc.perform(get("/api/auth/csrf"))
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
                    .andExpect(jsonPath("$.data").value((Object) null));
        }

        mockMvc.perform(wrongLogin(csrfCookie, email, remoteIp))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.data").value((Object) null))
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
