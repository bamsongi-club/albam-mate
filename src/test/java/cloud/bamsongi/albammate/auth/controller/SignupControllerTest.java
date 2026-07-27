package cloud.bamsongi.albammate.auth.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cloud.bamsongi.albammate.auth.service.SignupService;
import cloud.bamsongi.albammate.global.config.SecurityConfig;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;
import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;
import cloud.bamsongi.albammate.global.security.ApiAccessDeniedHandler;
import cloud.bamsongi.albammate.global.security.ApiAuthenticationEntryPoint;
import cloud.bamsongi.albammate.global.security.AuthenticationRequestLimiter;
import cloud.bamsongi.albammate.global.security.SecurityErrorResponseWriter;
import cloud.bamsongi.albammate.user.contract.UserAccount;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import jakarta.servlet.http.Cookie;
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

@WebMvcTest(controllers = {SignupController.class, CsrfController.class})
@Import({
    SecurityConfig.class,
    ApiAccessDeniedHandler.class,
    ApiAuthenticationEntryPoint.class,
    SecurityErrorResponseWriter.class,
    GlobalExceptionHandler.class,
    SignupControllerTest.TestBeans.class
})
class SignupControllerTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private AuthenticationRequestLimiter requestLimiter;

    @Autowired private UserAccountService userAccountService;

    @BeforeEach
    void resetMocks() {
        reset(requestLimiter, userAccountService);
    }

    @Test
    void CSRF가_있는_회원가입은_201과_UserSummary를_반환하고_세션을_만들지_않는다() throws Exception {
        when(userAccountService.createAccount("user@example.com", "123456789012345", "닉네임"))
                .thenReturn(new UserAccount(7L, "닉네임"));
        MvcResult csrfResult =
                mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
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
                .andExpect(jsonPath("$.data.email").doesNotExist())
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(header().doesNotExist("Set-Cookie"));

        org.mockito.Mockito.verify(requestLimiter).requireSignupAllowed("198.51.100.31");
    }

    @Test
    void DTO_검증에_실패하면_요청제한과_계정생성을_소모하지_않는다() throws Exception {
        MvcResult csrfResult =
                mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
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
    void 요청제한을_초과하면_사용자_생성_없이_429와_Retry_After를_반환한다() throws Exception {
        doThrow(new RateLimitExceededException(12))
                .when(requestLimiter)
                .requireSignupAllowed("198.51.100.32");
        MvcResult csrfResult =
                mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
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
    void CSRF가_없으면_컨트롤러까지_도달하지_않는다() throws Exception {
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

    private RequestPostProcessor remoteAddress(String remoteAddress) {
        return request -> {
            request.setRemoteAddr(remoteAddress);
            return request;
        };
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
    }
}
