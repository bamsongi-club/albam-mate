package cloud.bamsongi.albammate.auth.controller;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cloud.bamsongi.albammate.global.security.CurrentUserPrincipal;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ProfileHttpIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;

    @Test
    void 인증_HTTP_경로에서_프로필_조회와_수정이_계속_자신에게만_적용된다() throws Exception {
        User user =
                userRepository.saveAndFlush(
                        User.create("profile-http@example.com", "{bcrypt}hash", "이전 닉네임"));
        CsrfContext csrfContext = csrfContext(user.getId());

        mockMvc.perform(get("/api/users/me").with(currentUserAuthentication(user.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(user.getId()))
                .andExpect(jsonPath("$.data.nickname").value("이전 닉네임"));

        mockMvc.perform(
                        patch("/api/users/me")
                                .with(currentUserAuthentication(user.getId()))
                                .session(csrfContext.session())
                                .cookie(csrfContext.cookie())
                                .header("X-XSRF-TOKEN", csrfContext.cookie().getValue())
                                .contentType("application/json")
                                .content("{\"nickname\":\" 변경 닉네임 \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(user.getId()))
                .andExpect(jsonPath("$.data.nickname").value("변경 닉네임"))
                .andExpect(jsonPath("$.data.email").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());

        mockMvc.perform(get("/api/users/me").with(currentUserAuthentication(user.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("변경 닉네임"));
    }

    private CsrfContext csrfContext(long userId) throws Exception {
        MvcResult csrfResult =
                mockMvc.perform(get("/api/auth/csrf").with(currentUserAuthentication(userId)))
                        .andExpect(status().isOk())
                        .andReturn();
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(csrfCookie);
        return new CsrfContext(
                csrfCookie, (MockHttpSession) csrfResult.getRequest().getSession(false));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor
            currentUserAuthentication(long userId) {
        return authentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
    }

    private record CsrfContext(Cookie cookie, MockHttpSession session) {}
}
