package cloud.bamsongi.albammate.chat.match;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.socket.server.HandshakeHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import cloud.bamsongi.albammate.chat.websocket.ChatWebSocketProperties;
import cloud.bamsongi.albammate.global.config.SecurityConfig;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.global.security.currentuser.SecurityContextCurrentUserAccessor;
import cloud.bamsongi.albammate.global.security.error.ApiAccessDeniedHandler;
import cloud.bamsongi.albammate.global.security.error.ApiAuthenticationEntryPoint;
import cloud.bamsongi.albammate.global.security.error.SecurityErrorResponseWriter;
import cloud.bamsongi.albammate.matching.contract.MatchPartyAccessQuery;
import cloud.bamsongi.albammate.matching.contract.MatchPartyChatAccess;

/** T1(CHAT-T2): handshake의 인증·Origin·Party 접근 판정이 올바른 오류 봉투로 거절되는지 검증한다. */
@WebMvcTest(controllers = MatchChatWebSocketHandshakeController.class)
@Import({
	SecurityConfig.class,
	ApiAccessDeniedHandler.class,
	ApiAuthenticationEntryPoint.class,
	SecurityErrorResponseWriter.class,
	GlobalExceptionHandler.class,
	SecurityContextCurrentUserAccessor.class,
	MatchChatEndpointPolicyContributor.class,
	MatchChatWebSocketHandshakeControllerTest.TestBeans.class
})
class MatchChatWebSocketHandshakeControllerTest {

	private static final String ALLOWED_ORIGIN = "http://localhost:5173";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private MatchPartyAccessQuery matchPartyAccessQuery;

	@Test
	void 비로그인_handshake_요청은_UNAUTHENTICATED이고_접근_판정을_호출하지_않는다() throws Exception {
		clearInvocations(matchPartyAccessQuery);

		mockMvc.perform(handshakeGet(1L).header("Origin", ALLOWED_ORIGIN))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));

		verifyNoInteractions(matchPartyAccessQuery);
	}

	@Test
	void CSRF_토큰이_없어도_handshake는_거절되지_않는다() throws Exception {
		when(matchPartyAccessQuery.evaluateChatAccess(42L, 1L)).thenReturn(MatchPartyChatAccess.ALLOWED);

		mockMvc.perform(handshakeGet(1L).header("Origin", ALLOWED_ORIGIN).with(authenticationFor(42L)))
			.andExpect(result -> {});

		org.mockito.Mockito.verify(matchPartyAccessQuery).evaluateChatAccess(42L, 1L);
	}

	@Test
	void 허용되지_않은_Origin의_handshake_요청은_FORBIDDEN이고_접근_판정을_호출하지_않는다() throws Exception {
		clearInvocations(matchPartyAccessQuery);

		mockMvc
			.perform(
				handshakeGet(1L).header("Origin", "https://evil.example.com").with(authenticationFor(42L)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));

		verifyNoInteractions(matchPartyAccessQuery);
	}

	@Test
	void 준비중이거나_종료된_Party는_MATCH_CHAT_NOT_ACTIVE이다() throws Exception {
		when(matchPartyAccessQuery.evaluateChatAccess(42L, 2L)).thenReturn(MatchPartyChatAccess.NOT_ACTIVE);

		mockMvc
			.perform(handshakeGet(2L).header("Origin", ALLOWED_ORIGIN).with(authenticationFor(42L)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(ErrorCode.MATCH_CHAT_NOT_ACTIVE.getCode()));
	}

	@Test
	void 비참가자나_이탈한_참가자는_FORBIDDEN이다() throws Exception {
		when(matchPartyAccessQuery.evaluateChatAccess(42L, 3L)).thenReturn(MatchPartyChatAccess.FORBIDDEN);

		mockMvc
			.perform(handshakeGet(3L).header("Origin", ALLOWED_ORIGIN).with(authenticationFor(42L)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));
	}

	@Test
	void afterMessageId가_0이거나_음수면_VALIDATION_ERROR이고_접근_판정을_호출하지_않는다() throws Exception {
		clearInvocations(matchPartyAccessQuery);

		mockMvc
			.perform(
				handshakeGet(1L).param("afterMessageId", "0").header("Origin", ALLOWED_ORIGIN)
					.with(authenticationFor(42L)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));

		verifyNoInteractions(matchPartyAccessQuery);
	}

	/** 운영 프로필이 허용 Origin을 아직 주입하지 않은 상태를 재현한다. */
	@Nested
	@TestPropertySource(properties = "app.chat.websocket.allowed-origin=")
	class BlankAllowedOriginTest {

		@Autowired
		private MockMvc mockMvc;
		@Autowired
		private MatchPartyAccessQuery matchPartyAccessQuery;

		@Test
		void 허용_Origin이_비어_있으면_개발_Origin도_FORBIDDEN이고_접근_판정을_호출하지_않는다() throws Exception {
			clearInvocations(matchPartyAccessQuery);

			mockMvc
				.perform(handshakeGet(1L).header("Origin", ALLOWED_ORIGIN).with(authenticationFor(42L)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));

			verifyNoInteractions(matchPartyAccessQuery);
		}
	}

	private MockHttpServletRequestBuilder handshakeGet(long partyId) {
		return get("/api/matches/parties/{partyId}/chat/ws", partyId);
	}

	private RequestPostProcessor authenticationFor(long userId) {
		return authentication(
			new UsernamePasswordAuthenticationToken(
				new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestBeans {

		@Bean
		MatchPartyAccessQuery matchPartyAccessQuery() {
			return Mockito.mock(MatchPartyAccessQuery.class);
		}

		@Bean
		ChatWebSocketProperties chatWebSocketProperties(
			@Value("${app.chat.websocket.allowed-origin:" + ALLOWED_ORIGIN + "}")
			String allowedOrigin) {
			ChatWebSocketProperties properties = new ChatWebSocketProperties();
			properties.setAllowedOrigin(allowedOrigin);
			return properties;
		}

		@Bean
		HandshakeHandler chatHandshakeHandler() {
			return new DefaultHandshakeHandler();
		}

		@Bean
		MatchChatWebSocketHandler matchChatWebSocketHandler() {
			return Mockito.mock(MatchChatWebSocketHandler.class);
		}
	}
}
