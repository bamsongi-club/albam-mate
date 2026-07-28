package cloud.bamsongi.albammate.room.controller;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cloud.bamsongi.albammate.global.config.SecurityConfig;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;
import cloud.bamsongi.albammate.global.security.ApiAccessDeniedHandler;
import cloud.bamsongi.albammate.global.security.ApiAuthenticationEntryPoint;
import cloud.bamsongi.albammate.global.security.CurrentUserPrincipal;
import cloud.bamsongi.albammate.global.security.SecurityContextCurrentUserAccessor;
import cloud.bamsongi.albammate.global.security.SecurityErrorResponseWriter;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.service.RoomParticipationCancelService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(controllers = RoomParticipationController.class)
@Import({
    SecurityConfig.class,
    ApiAccessDeniedHandler.class,
    ApiAuthenticationEntryPoint.class,
    SecurityErrorResponseWriter.class,
    GlobalExceptionHandler.class,
    SecurityContextCurrentUserAccessor.class,
    RoomParticipationControllerTest.TestBeans.class
})
class RoomParticipationControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private RoomParticipationCancelService roomParticipationCancelService;

    @Test
    void 인증없는_참가_취소는_UNAUTHENTICATED다() throws Exception {
        clearInvocations(roomParticipationCancelService);

        mockMvc.perform(delete("/api/rooms/1/participants/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));

        verifyNoInteractions(roomParticipationCancelService);
    }

    @Test
    void 인증만_있는_참가_취소는_CSRF_TOKEN_INVALID다() throws Exception {
        clearInvocations(roomParticipationCancelService);

        mockMvc.perform(delete("/api/rooms/1/participants/me").with(authenticationFor(42L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));

        verifyNoInteractions(roomParticipationCancelService);
    }

    @Test
    void 잘못된_CSRF_토큰_참가_취소는_CSRF_TOKEN_INVALID다() throws Exception {
        clearInvocations(roomParticipationCancelService);

        mockMvc.perform(
                        delete("/api/rooms/1/participants/me")
                                .with(authenticationFor(42L))
                                .with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));

        verifyNoInteractions(roomParticipationCancelService);
    }

    @Test
    void 인증과_CSRF가_있는_참가_취소는_200_응답_봉투를_반환한다() throws Exception {
        when(roomParticipationCancelService.cancelParticipation(42L, 1L))
                .thenReturn(
                        new RoomParticipationResponse(
                                1L, ParticipationStatus.CANCELED, RoomStatus.RECRUITING, 1, 2));

        mockMvc.perform(
                        delete("/api/rooms/1/participants/me")
                                .with(authenticationFor(42L))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.roomId").value(1))
                .andExpect(jsonPath("$.data.participationStatus").value("CANCELED"))
                .andExpect(jsonPath("$.data.roomStatus").value("RECRUITING"))
                .andExpect(jsonPath("$.data.participantCount").value(1))
                .andExpect(jsonPath("$.data.remainingRecruitmentSeats").value(2));

        verify(roomParticipationCancelService).cancelParticipation(42L, 1L);
    }

    @Test
    void 주최자_참가_취소는_FORBIDDEN_응답_봉투를_반환한다() throws Exception {
        when(roomParticipationCancelService.cancelParticipation(42L, 1L))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

        mockMvc.perform(
                        delete("/api/rooms/1/participants/me")
                                .with(authenticationFor(42L))
                                .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));
    }

    @Test
    void 양수가_아닌_방_ID는_VALIDATION_ERROR이고_서비스를_호출하지_않는다() throws Exception {
        clearInvocations(roomParticipationCancelService);

        mockMvc.perform(
                        delete("/api/rooms/0/participants/me")
                                .with(authenticationFor(42L))
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));

        verifyNoInteractions(roomParticipationCancelService);
    }

    private RequestPostProcessor authenticationFor(long userId) {
        return authentication(
                new UsernamePasswordAuthenticationToken(
                        new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        RoomParticipationCancelService roomParticipationCancelService() {
            return Mockito.mock(RoomParticipationCancelService.class);
        }
    }
}
