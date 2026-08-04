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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import cloud.bamsongi.albammate.chat.dto.ChatMessageResponse;
import cloud.bamsongi.albammate.chat.dto.ChatMessageSendRequest;
import cloud.bamsongi.albammate.chat.dto.ChatMessageSender;
import cloud.bamsongi.albammate.chat.service.ChatMessageCommandService;
import cloud.bamsongi.albammate.chat.service.ChatMessageSendResult;
import cloud.bamsongi.albammate.global.config.SecurityConfig;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.global.security.currentuser.SecurityContextCurrentUserAccessor;
import cloud.bamsongi.albammate.global.security.error.ApiAccessDeniedHandler;
import cloud.bamsongi.albammate.global.security.error.ApiAuthenticationEntryPoint;
import cloud.bamsongi.albammate.global.security.error.SecurityErrorResponseWriter;

@WebMvcTest(controllers = ChatMessageController.class)
@Import({
	SecurityConfig.class,
	ApiAccessDeniedHandler.class,
	ApiAuthenticationEntryPoint.class,
	SecurityErrorResponseWriter.class,
	GlobalExceptionHandler.class,
	SecurityContextCurrentUserAccessor.class,
	ChatMessageControllerTest.TestBeans.class
})
class ChatMessageControllerTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private ChatMessageCommandService chatMessageCommandService;

	@Test
	void 비로그인_POST는_UNAUTHENTICATED이고_명령을_호출하지_않는다() throws Exception {
		clearInvocations(chatMessageCommandService);

		mockMvc.perform(messagePost(1L))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));

		verifyNoInteractions(chatMessageCommandService);
	}

	@Test
	void 인증만_있는_POST는_CSRF_TOKEN_INVALID이고_명령을_호출하지_않는다() throws Exception {
		clearInvocations(chatMessageCommandService);

		mockMvc.perform(messagePost(1L).with(authenticationFor(42L)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));

		verifyNoInteractions(chatMessageCommandService);
	}

	@Test
	void 최초_POST는_201_응답_봉투와_ChatMessage만_반환한다() throws Exception {
		when(chatMessageCommandService.send(42L, 1L, request()))
			.thenReturn(new ChatMessageSendResult(response(), true));

		mockMvc.perform(messagePost(1L).with(authenticationFor(42L)).with(csrf()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value(201))
			.andExpect(jsonPath("$.data.messageId").value(10))
			.andExpect(jsonPath("$.data.roomId").value(1))
			.andExpect(jsonPath("$.data.clientMessageId").value("client-1"))
			.andExpect(jsonPath("$.data.sender.nickname").value("작성자"))
			.andExpect(jsonPath("$.data.content").value("안녕하세요"))
			.andExpect(jsonPath("$.data.senderUserId").doesNotExist());
	}

	@Test
	void 동일_멱등_POST는_200과_최초_메시지를_반환한다() throws Exception {
		when(chatMessageCommandService.send(42L, 1L, request()))
			.thenReturn(new ChatMessageSendResult(response(), false));

		mockMvc.perform(messagePost(1L).with(authenticationFor(42L)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.messageId").value(10));
	}

	@Test
	void 양수가_아닌_roomId는_VALIDATION_ERROR이고_명령을_호출하지_않는다() throws Exception {
		clearInvocations(chatMessageCommandService);

		mockMvc.perform(messagePost(0L).with(authenticationFor(42L)).with(csrf()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));

		verifyNoInteractions(chatMessageCommandService);
	}

	@Test
	void 숫자가_아닌_roomId는_VALIDATION_ERROR이고_명령을_호출하지_않는다() throws Exception {
		clearInvocations(chatMessageCommandService);

		mockMvc.perform(
			post("/api/rooms/not-a-number/chat/messages")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"clientMessageId\":\"client-1\",\"content\":\"안녕하세요\"}")
				.with(authenticationFor(42L))
				.with(csrf()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));

		verifyNoInteractions(chatMessageCommandService);
	}

	@Test
	void 서비스의_채팅_오류는_엔드포인트_공통_오류_봉투로_변환된다() throws Exception {
		when(chatMessageCommandService.send(42L, 404L, request()))
			.thenThrow(new BusinessException(ErrorCode.ROOM_NOT_FOUND));
		when(chatMessageCommandService.send(42L, 403L, request()))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
		when(chatMessageCommandService.send(42L, 400L, request()))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR));

		assertErrorEnvelope(404L, ErrorCode.ROOM_NOT_FOUND);
		assertErrorEnvelope(403L, ErrorCode.FORBIDDEN);
		assertErrorEnvelope(400L, ErrorCode.VALIDATION_ERROR);
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder messagePost(long roomId) {
		return post("/api/rooms/{roomId}/chat/messages", roomId)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"clientMessageId\":\"client-1\",\"content\":\"안녕하세요\"}");
	}

	private void assertErrorEnvelope(long roomId, ErrorCode errorCode) throws Exception {
		mockMvc.perform(messagePost(roomId).with(authenticationFor(42L)).with(csrf()))
			.andExpect(status().is(errorCode.getStatus()))
			.andExpect(jsonPath("$.status").value(errorCode.getStatus()))
			.andExpect(jsonPath("$.code").value(errorCode.getCode()));
	}

	private ChatMessageSendRequest request() {
		return new ChatMessageSendRequest("client-1", "안녕하세요");
	}

	private RequestPostProcessor authenticationFor(long userId) {
		return authentication(
			new UsernamePasswordAuthenticationToken(
				new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
	}

	private ChatMessageResponse response() {
		return new ChatMessageResponse(
			10L,
			1L,
			"client-1",
			new ChatMessageSender("작성자"),
			"안녕하세요",
			Instant.parse("2026-08-04T00:00:00Z"));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestBeans {

		@Bean
		ChatMessageCommandService chatMessageCommandService() {
			return Mockito.mock(ChatMessageCommandService.class);
		}
	}
}
