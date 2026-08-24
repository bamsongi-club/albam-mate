package cloud.bamsongi.albammate.chat.controller;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import cloud.bamsongi.albammate.chat.dto.ChatRoomReadStateResponse;
import cloud.bamsongi.albammate.chat.service.ChatRoomReadService;
import cloud.bamsongi.albammate.global.config.SecurityConfig;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.global.security.currentuser.SecurityContextCurrentUserAccessor;
import cloud.bamsongi.albammate.global.security.error.ApiAccessDeniedHandler;
import cloud.bamsongi.albammate.global.security.error.ApiAuthenticationEntryPoint;
import cloud.bamsongi.albammate.global.security.error.SecurityErrorResponseWriter;

/**
 * CHAT-07 읽음 처리 API(POST /api/rooms/{roomId}/chat/read)의 HTTP 경계를 검증한다.
 *
 * <p>비참가자·비로그인·CSRF 오류·잘못된 요청은 {@link ChatRoomReadService}를 mock으로 대체해 서비스가 던지는
 * 오류가 공통 오류 봉투로 올바르게 변환되는지 확인하며, 실제 접근 판정·참가·방 상태 검증은
 * {@code ChatRoomReadServiceIntegrationTest}가 소유한다.
 */
@WebMvcTest(controllers = ChatRoomReadController.class)
@Import({
	SecurityConfig.class,
	ApiAccessDeniedHandler.class,
	ApiAuthenticationEntryPoint.class,
	SecurityErrorResponseWriter.class,
	GlobalExceptionHandler.class,
	SecurityContextCurrentUserAccessor.class,
	ChatRoomReadControllerTest.TestBeans.class
})
class ChatRoomReadControllerTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private ChatRoomReadService chatRoomReadService;

	@Test
	void 비로그인_POST는_UNAUTHENTICATED이고_서비스를_호출하지_않는다() throws Exception {
		clearInvocations(chatRoomReadService);

		mockMvc.perform(readPost(1L))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));

		verifyNoInteractions(chatRoomReadService);
	}

	@Test
	void 인증만_있고_CSRF가_없는_POST는_CSRF_TOKEN_INVALID이고_서비스를_호출하지_않는다() throws Exception {
		clearInvocations(chatRoomReadService);

		mockMvc.perform(readPost(1L).with(authenticationFor(42L)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));

		verifyNoInteractions(chatRoomReadService);
	}

	@Test
	void 성공_요청은_200과_ChatRoomReadStateResponse를_반환한다() throws Exception {
		when(chatRoomReadService.markRead(42L, 1L, 1042L))
			.thenReturn(new ChatRoomReadStateResponse(1L, 1042L, Instant.parse("2026-08-19T00:00:00Z")));

		mockMvc.perform(readPost(1L).with(authenticationFor(42L)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.roomId").value(1))
			.andExpect(jsonPath("$.data.lastReadMessageId").value(1042))
			.andExpect(jsonPath("$.data.updatedAt").exists());
	}

	@Test
	void 비참가자의_서비스_FORBIDDEN_오류는_403_엔드포인트_오류_봉투로_변환된다() throws Exception {
		when(chatRoomReadService.markRead(42L, 1L, 1042L))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		mockMvc.perform(readPost(1L).with(authenticationFor(42L)).with(csrf()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.status").value(403))
			.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));
	}

	@Test
	void 존재하지_않는_방의_서비스_ROOM_NOT_FOUND_오류는_404_엔드포인트_오류_봉투로_변환된다() throws Exception {
		when(chatRoomReadService.markRead(42L, 404L, 1042L))
			.thenThrow(new BusinessException(ErrorCode.ROOM_NOT_FOUND));

		mockMvc.perform(readPost(404L).with(authenticationFor(42L)).with(csrf()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value(ErrorCode.ROOM_NOT_FOUND.getCode()));
	}

	@Test
	void 존재하지_않거나_미래의_upToMessageId는_서비스의_VALIDATION_ERROR_오류로_400_응답된다() throws Exception {
		when(chatRoomReadService.markRead(42L, 1L, 999999L))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR));

		mockMvc.perform(readPost(1L, 999999L).with(authenticationFor(42L)).with(csrf()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
	}

	@Test
	void 양수가_아닌_roomId는_VALIDATION_ERROR이고_서비스를_호출하지_않는다() throws Exception {
		clearInvocations(chatRoomReadService);

		mockMvc.perform(readPost(0L).with(authenticationFor(42L)).with(csrf()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));

		verifyNoInteractions(chatRoomReadService);
	}

	private MockHttpServletRequestBuilder readPost(long roomId) {
		return readPost(roomId, 1042L);
	}

	private MockHttpServletRequestBuilder readPost(long roomId, long upToMessageId) {
		return post("/api/rooms/{roomId}/chat/read", roomId)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"upToMessageId\":" + upToMessageId + "}");
	}

	private RequestPostProcessor authenticationFor(long userId) {
		return authentication(
			new UsernamePasswordAuthenticationToken(
				new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestBeans {

		@Bean
		ChatRoomReadService chatRoomReadService() {
			return Mockito.mock(ChatRoomReadService.class);
		}
	}
}
