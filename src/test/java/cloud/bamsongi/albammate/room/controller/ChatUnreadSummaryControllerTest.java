package cloud.bamsongi.albammate.room.controller;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import cloud.bamsongi.albammate.global.config.SecurityConfig;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.global.security.currentuser.SecurityContextCurrentUserAccessor;
import cloud.bamsongi.albammate.global.security.error.ApiAccessDeniedHandler;
import cloud.bamsongi.albammate.global.security.error.ApiAuthenticationEntryPoint;
import cloud.bamsongi.albammate.global.security.error.SecurityErrorResponseWriter;
import cloud.bamsongi.albammate.room.service.query.ChatUnreadSummaryQueryService;

@WebMvcTest(controllers = ChatUnreadSummaryController.class)
@Import({
	SecurityConfig.class,
	ApiAccessDeniedHandler.class,
	ApiAuthenticationEntryPoint.class,
	SecurityErrorResponseWriter.class,
	GlobalExceptionHandler.class,
	SecurityContextCurrentUserAccessor.class,
	ChatUnreadSummaryControllerTest.TestBeans.class
})
class ChatUnreadSummaryControllerTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private ChatUnreadSummaryQueryService chatUnreadSummaryQueryService;

	@Test
	void 인증없는_미읽음_요약_조회는_UNAUTHENTICATED다() throws Exception {
		clearInvocations(chatUnreadSummaryQueryService);

		mockMvc.perform(get("/api/users/me/chat/unread-summary"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));

		verifyNoInteractions(chatUnreadSummaryQueryService);
	}

	@Test
	void 인증된_조회는_응답_봉투와_unreadRoomCount를_반환한다() throws Exception {
		when(chatUnreadSummaryQueryService.countUnreadRooms(42L)).thenReturn(3);

		mockMvc.perform(get("/api/users/me/chat/unread-summary").with(authenticationFor(42L)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.unreadRoomCount").value(3));

		verify(chatUnreadSummaryQueryService).countUnreadRooms(42L);
	}

	private RequestPostProcessor authenticationFor(long userId) {
		return authentication(
			new UsernamePasswordAuthenticationToken(
				new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestBeans {

		@Bean
		ChatUnreadSummaryQueryService chatUnreadSummaryQueryService() {
			return Mockito.mock(ChatUnreadSummaryQueryService.class);
		}
	}
}
