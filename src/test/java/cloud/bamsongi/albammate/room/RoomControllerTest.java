package cloud.bamsongi.albammate.room;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cloud.bamsongi.albammate.global.config.SecurityConfig;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;
import cloud.bamsongi.albammate.global.security.ApiAccessDeniedHandler;
import cloud.bamsongi.albammate.global.security.ApiAuthenticationEntryPoint;
import cloud.bamsongi.albammate.global.security.CurrentUserPrincipal;
import cloud.bamsongi.albammate.global.security.SecurityContextCurrentUserAccessor;
import cloud.bamsongi.albammate.global.security.SecurityErrorResponseWriter;
import cloud.bamsongi.albammate.room.entity.ExperienceLevel;
import cloud.bamsongi.albammate.room.entity.RoomStatus;
import cloud.bamsongi.albammate.room.entity.RoomType;
import java.time.Instant;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(controllers = RoomController.class)
@Import({
    SecurityConfig.class,
    ApiAccessDeniedHandler.class,
    ApiAuthenticationEntryPoint.class,
    SecurityErrorResponseWriter.class,
    GlobalExceptionHandler.class,
    SecurityContextCurrentUserAccessor.class,
    RoomControllerTest.TestBeans.class
})
class RoomControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private RoomCreateService roomCreateService;

    @Test
    void 인증없는_방_생성은_UNAUTHENTICATED다() throws Exception {
        clearInvocations(roomCreateService);
        mockMvc.perform(post("/api/rooms").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
        verifyNoInteractions(roomCreateService);
    }

    @Test
    void 인증과_CSRF가_있는_유효한_요청은_201_응답_봉투를_반환한다() throws Exception {
        ParticipantRoomResponse response = response();
        when(roomCreateService.createRoom(anyLong(), any(CreateRoomRequest.class)))
                .thenReturn(response);
        mockMvc.perform(
                        post("/api/rooms")
                                .with(csrf())
                                .with(authenticationFor(42L))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.roomType").value("PERSON_FOCUSED"))
                .andExpect(jsonPath("$.data.title").value("사람 중심"))
                .andExpect(jsonPath("$.data.description").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.game").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.experienceLevel").value("ALL_LEVELS"))
                .andExpect(jsonPath("$.data.isRulemasterLed").value(false))
                .andExpect(jsonPath("$.data.startsAt").value("2099-01-01T19:00:00+09:00"))
                .andExpect(jsonPath("$.data.region").value("홍대"))
                .andExpect(jsonPath("$.data.recruitmentCapacity").value(3))
                .andExpect(jsonPath("$.data.participantCount").value(1))
                .andExpect(jsonPath("$.data.remainingRecruitmentSeats").value(3))
                .andExpect(jsonPath("$.data.status").value("RECRUITING"))
                .andExpect(jsonPath("$.data.joinable").value(false))
                .andExpect(jsonPath("$.data.myRole").value("HOST"))
                .andExpect(jsonPath("$.data.place").value("홍대 장소"))
                .andExpect(jsonPath("$.data.host.nickname").value("방장"))
                .andExpect(jsonPath("$.data.participants").isArray())
                .andExpect(jsonPath("$.data.participants").value(Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data.participants[0].nickname").value("방장"));
    }

    @Test
    void 인증과_CSRF가_있어도_요청값_검증은_VALIDATION_ERROR다() throws Exception {
        mockMvc.perform(
                        post("/api/rooms")
                                .with(csrf())
                                .with(authenticationFor(42L))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"roomType\":\"PERSON_FOCUSED\",\"title\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
    }

    @Test
    void 인증만_있는_유효한_요청은_CSRF_TOKEN_INVALID이고_Service를_호출하지_않는다() throws Exception {
        clearInvocations(roomCreateService);

        mockMvc.perform(
                        post("/api/rooms")
                                .with(authenticationFor(42L))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));

        verifyNoInteractions(roomCreateService);
    }

    private RequestPostProcessor authenticationFor(long userId) {
        return authentication(
                new UsernamePasswordAuthenticationToken(
                        new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
    }

    private ParticipantRoomResponse response() {
        NicknameSummary host = new NicknameSummary("방장");
        return new ParticipantRoomResponse(
                1L,
                RoomType.PERSON_FOCUSED,
                "사람 중심",
                null,
                null,
                ExperienceLevel.ALL_LEVELS,
                false,
                Instant.parse("2099-01-01T10:00:00Z"),
                "홍대",
                3,
                1,
                3,
                RoomStatus.RECRUITING,
                false,
                MyRole.HOST,
                "홍대 장소",
                host,
                List.of(host));
    }

    private String validJson() {
        return """
                {
                  "roomType": "PERSON_FOCUSED",
                  "title": "사람 중심",
                  "description": null,
                  "gameId": null,
                  "experienceLevel": "ALL_LEVELS",
                  "isRulemasterLed": false,
                  "startsAt": "2099-01-01T19:00:00+09:00",
                  "place": "홍대 장소",
                  "recruitmentCapacity": 3
                }
                """;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        RoomCreateService roomCreateService() {
            return Mockito.mock(RoomCreateService.class);
        }
    }
}
