package cloud.bamsongi.albammate.chat.match;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

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

import cloud.bamsongi.albammate.chat.match.service.MatchChatMessageCommandService;
import cloud.bamsongi.albammate.chat.match.service.MatchChatMessageHistoryQueryService;
import cloud.bamsongi.albammate.chat.match.service.MatchChatMessageSendResult;
import cloud.bamsongi.albammate.global.config.SecurityConfig;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.global.security.currentuser.SecurityContextCurrentUserAccessor;
import cloud.bamsongi.albammate.global.security.error.ApiAccessDeniedHandler;
import cloud.bamsongi.albammate.global.security.error.ApiAuthenticationEntryPoint;
import cloud.bamsongi.albammate.global.security.error.SecurityErrorResponseWriter;

/** CHAT-T3·CHAT-T4 HTTP 경계 — 인증·CSRF·path/query 검증과 서비스 오류 전달을 검증한다. */
@WebMvcTest(controllers = MatchChatMessageController.class)
@Import({
	SecurityConfig.class,
	ApiAccessDeniedHandler.class,
	ApiAuthenticationEntryPoint.class,
	SecurityErrorResponseWriter.class,
	GlobalExceptionHandler.class,
	SecurityContextCurrentUserAccessor.class,
	MatchChatEndpointPolicyContributor.class,
	MatchChatMessageControllerTest.TestBeans.class
})
class MatchChatMessageControllerTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private MatchChatMessageCommandService matchChatMessageCommandService;
	@Autowired
	private MatchChatMessageHistoryQueryService matchChatMessageHistoryQueryService;

	@Test
	void 비로그인_POST는_UNAUTHENTICATED이고_명령을_호출하지_않는다() throws Exception {
		clearInvocations(matchChatMessageCommandService);

		mockMvc.perform(messagePost(1L))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));

		verifyNoInteractions(matchChatMessageCommandService);
	}

	@Test
	void 인증만_있는_POST는_CSRF_TOKEN_INVALID이고_명령을_호출하지_않는다() throws Exception {
		clearInvocations(matchChatMessageCommandService);

		mockMvc.perform(messagePost(1L).with(authenticationFor(42L)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));

		verifyNoInteractions(matchChatMessageCommandService);
	}

	@Test
	void 최초_POST는_201_응답_봉투와_MatchChatMessage만_반환한다() throws Exception {
		when(matchChatMessageCommandService.send(42L, 1L, request()))
			.thenReturn(new MatchChatMessageSendResult(response(), true));

		mockMvc.perform(messagePost(1L).with(authenticationFor(42L)).with(csrf()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value(201))
			.andExpect(jsonPath("$.data.messageId").value(10))
			.andExpect(jsonPath("$.data.partyId").value(1))
			.andExpect(jsonPath("$.data.type").value("USER"))
			.andExpect(jsonPath("$.data.clientMessageId").value("client-1"))
			.andExpect(jsonPath("$.data.sender.participantRef").value("ref-1"))
			.andExpect(jsonPath("$.data.sender.nickname").value("작성자"))
			.andExpect(jsonPath("$.data.isMine").value(true))
			.andExpect(jsonPath("$.data.content").value("같이 플레이해요."));
	}

	@Test
	void 동일_멱등_POST는_200과_최초_메시지를_반환한다() throws Exception {
		when(matchChatMessageCommandService.send(42L, 1L, request()))
			.thenReturn(new MatchChatMessageSendResult(response(), false));

		mockMvc.perform(messagePost(1L).with(authenticationFor(42L)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.messageId").value(10));
	}

	@Test
	void 양수가_아닌_partyId는_VALIDATION_ERROR이고_명령을_호출하지_않는다() throws Exception {
		clearInvocations(matchChatMessageCommandService);

		mockMvc.perform(messagePost(0L).with(authenticationFor(42L)).with(csrf()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));

		verifyNoInteractions(matchChatMessageCommandService);
	}

	@Test
	void 서비스의_채팅_오류는_엔드포인트_공통_오류_봉투로_변환된다() throws Exception {
		when(matchChatMessageCommandService.send(42L, 403L, request()))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
		when(matchChatMessageCommandService.send(42L, 409L, request()))
			.thenThrow(new BusinessException(ErrorCode.MATCH_CHAT_NOT_ACTIVE));
		when(matchChatMessageCommandService.send(42L, 400L, request()))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR));

		assertPostErrorEnvelope(403L, ErrorCode.FORBIDDEN);
		assertPostErrorEnvelope(409L, ErrorCode.MATCH_CHAT_NOT_ACTIVE);
		assertPostErrorEnvelope(400L, ErrorCode.VALIDATION_ERROR);
	}

	@Test
	void 비로그인_GET은_UNAUTHENTICATED고_조회를_호출하지_않는다() throws Exception {
		clearInvocations(matchChatMessageHistoryQueryService);

		mockMvc.perform(historyGet(1L))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));

		verifyNoInteractions(matchChatMessageHistoryQueryService);
	}

	@Test
	void 인증만_있으면_CSRF_없이도_GET_이력_조회가_허용된다() throws Exception {
		when(matchChatMessageHistoryQueryService.history(42L, 1L, null, 50))
			.thenReturn(new MatchChatMessagePageResponse(List.of(), null, false));

		mockMvc.perform(historyGet(1L).with(authenticationFor(42L)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.hasNext").value(false));
	}

	@Test
	void size_100_요청은_허용하고_101_요청은_VALIDATION_ERROR고_조회를_호출하지_않는다() throws Exception {
		when(matchChatMessageHistoryQueryService.history(42L, 1L, null, 100))
			.thenReturn(new MatchChatMessagePageResponse(List.of(), null, false));

		mockMvc.perform(historyGet(1L).param("size", "100").with(authenticationFor(42L)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.hasNext").value(false))
			.andExpect(jsonPath("$.data.nextBeforeMessageId").doesNotExist());

		clearInvocations(matchChatMessageHistoryQueryService);
		mockMvc.perform(historyGet(1L).param("size", "101").with(authenticationFor(42L)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		verifyNoInteractions(matchChatMessageHistoryQueryService);
	}

	@Test
	void 이력_조회_query_parameter_검증_실패는_VALIDATION_ERROR고_조회를_호출하지_않는다() throws Exception {
		clearInvocations(matchChatMessageHistoryQueryService);

		mockMvc.perform(historyGet(0L).with(authenticationFor(42L)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		mockMvc.perform(historyGet(1L).param("beforeMessageId", "0").with(authenticationFor(42L)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		mockMvc.perform(historyGet(1L).param("size", "0").with(authenticationFor(42L)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));

		verifyNoInteractions(matchChatMessageHistoryQueryService);
	}

	private MockHttpServletRequestBuilder historyGet(long partyId) {
		return get("/api/matches/parties/{partyId}/chat/messages", partyId);
	}

	private MockHttpServletRequestBuilder messagePost(long partyId) {
		return post("/api/matches/parties/{partyId}/chat/messages", partyId)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"clientMessageId\":\"client-1\",\"content\":\"같이 플레이해요.\"}");
	}

	private void assertPostErrorEnvelope(long partyId, ErrorCode errorCode) throws Exception {
		mockMvc.perform(messagePost(partyId).with(authenticationFor(42L)).with(csrf()))
			.andExpect(status().is(errorCode.getStatus()))
			.andExpect(jsonPath("$.status").value(errorCode.getStatus()))
			.andExpect(jsonPath("$.code").value(errorCode.getCode()));
	}

	private MatchChatMessageSendRequest request() {
		return new MatchChatMessageSendRequest("client-1", "같이 플레이해요.");
	}

	private RequestPostProcessor authenticationFor(long userId) {
		return authentication(
			new UsernamePasswordAuthenticationToken(
				new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
	}

	private MatchChatMessageResponse response() {
		return new MatchChatMessageResponse(
			10L,
			1L,
			MatchChatMessageType.USER,
			"client-1",
			new MatchChatSender("ref-1", "작성자"),
			true,
			"같이 플레이해요.",
			Instant.parse("2026-08-19T00:00:00Z"));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestBeans {

		@Bean
		MatchChatMessageCommandService matchChatMessageCommandService() {
			return Mockito.mock(MatchChatMessageCommandService.class);
		}

		@Bean
		MatchChatMessageHistoryQueryService matchChatMessageHistoryQueryService() {
			return Mockito.mock(MatchChatMessageHistoryQueryService.class);
		}
	}
}
