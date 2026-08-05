package cloud.bamsongi.albammate.chat.websocket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

import cloud.bamsongi.albammate.global.config.SecurityConfig;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.global.security.currentuser.SecurityContextCurrentUserAccessor;
import cloud.bamsongi.albammate.global.security.error.ApiAccessDeniedHandler;
import cloud.bamsongi.albammate.global.security.error.ApiAuthenticationEntryPoint;
import cloud.bamsongi.albammate.global.security.error.SecurityErrorResponseWriter;
import cloud.bamsongi.albammate.room.contract.ChatAccessGuard;

/** T1·T2: handshake의 인증·Origin·방 접근 판정이 올바른 오류 봉투로 거절되는지 검증한다. */
@WebMvcTest(controllers = ChatWebSocketHandshakeController.class)
@Import({
	SecurityConfig.class,
	ApiAccessDeniedHandler.class,
	ApiAuthenticationEntryPoint.class,
	SecurityErrorResponseWriter.class,
	GlobalExceptionHandler.class,
	SecurityContextCurrentUserAccessor.class,
	ChatWebSocketHandshakeControllerTest.TestBeans.class
})
class ChatWebSocketHandshakeControllerTest {

	private static final String ALLOWED_ORIGIN = "http://localhost:5173";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private ChatAccessGuard chatAccessGuard;

	@Test
	void 비로그인_handshake_요청은_UNAUTHENTICATED이고_접근_판정을_호출하지_않는다() throws Exception {
		clearInvocations(chatAccessGuard);

		mockMvc.perform(handshakeGet(1L).header("Origin", ALLOWED_ORIGIN))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));

		verifyNoInteractions(chatAccessGuard);
	}

	@Test
	void 허용되지_않은_Origin의_handshake_요청은_FORBIDDEN이고_접근_판정을_호출하지_않는다() throws Exception {
		clearInvocations(chatAccessGuard);

		mockMvc
			.perform(
				handshakeGet(1L).header("Origin", "https://evil.example.com").with(authenticationFor(42L)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));

		verifyNoInteractions(chatAccessGuard);
	}

	@Test
	void 허용된_Origin이어도_방이_없으면_ROOM_NOT_FOUND이다() throws Exception {
		when(chatAccessGuard.executeWithAccess(eq(42L), eq(404L), any()))
			.thenThrow(new BusinessException(ErrorCode.ROOM_NOT_FOUND));

		mockMvc
			.perform(handshakeGet(404L).header("Origin", ALLOWED_ORIGIN).with(authenticationFor(42L)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value(ErrorCode.ROOM_NOT_FOUND.getCode()));
	}

	@Test
	void 허용된_Origin이어도_비관계자나_최종_상태_방이면_FORBIDDEN이다() throws Exception {
		when(chatAccessGuard.executeWithAccess(eq(42L), eq(403L), any()))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		mockMvc
			.perform(handshakeGet(403L).header("Origin", ALLOWED_ORIGIN).with(authenticationFor(42L)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));
	}

	@Test
	void T6_afterMessageId가_0이거나_음수면_VALIDATION_ERROR이고_접근_판정을_호출하지_않는다() throws Exception {
		clearInvocations(chatAccessGuard);

		mockMvc
			.perform(
				handshakeGet(1L).param("afterMessageId", "0").header("Origin", ALLOWED_ORIGIN)
					.with(authenticationFor(42L)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		mockMvc
			.perform(
				handshakeGet(1L).param("afterMessageId", "-1").header("Origin", ALLOWED_ORIGIN)
					.with(authenticationFor(42L)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));

		verifyNoInteractions(chatAccessGuard);
	}

	@Test
	void T6_afterMessageId가_숫자가_아니면_VALIDATION_ERROR이고_접근_판정을_호출하지_않는다() throws Exception {
		clearInvocations(chatAccessGuard);

		mockMvc
			.perform(
				handshakeGet(1L).param("afterMessageId", "not-a-number").header("Origin", ALLOWED_ORIGIN)
					.with(authenticationFor(42L)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));

		verifyNoInteractions(chatAccessGuard);
	}

	@Test
	void T6_존재하지_않는_양수_afterMessageId는_검증을_통과해_접근_판정까지_도달한다() throws Exception {
		clearInvocations(chatAccessGuard);

		mockMvc
			.perform(
				handshakeGet(1L).param("afterMessageId", "999999").header("Origin", ALLOWED_ORIGIN)
					.with(authenticationFor(42L)))
			.andExpect(result -> {});

		org.mockito.Mockito.verify(chatAccessGuard).executeWithAccess(eq(42L), eq(1L), any());
	}

	/** 운영 프로필이 허용 Origin을 아직 주입하지 않은 상태를 재현한다. */
	@Nested
	@TestPropertySource(properties = "app.chat.websocket.allowed-origin=")
	class BlankAllowedOriginTest {

		@Autowired
		private MockMvc mockMvc;
		@Autowired
		private ChatAccessGuard chatAccessGuard;

		@Test
		void 허용_Origin이_비어_있으면_개발_Origin도_FORBIDDEN이고_접근_판정을_호출하지_않는다() throws Exception {
			clearInvocations(chatAccessGuard);

			mockMvc
				.perform(handshakeGet(1L).header("Origin", ALLOWED_ORIGIN).with(authenticationFor(42L)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));

			verifyNoInteractions(chatAccessGuard);
		}
	}

	private MockHttpServletRequestBuilder handshakeGet(long roomId) {
		return get("/api/rooms/{roomId}/chat/ws", roomId);
	}

	private RequestPostProcessor authenticationFor(long userId) {
		return authentication(
			new UsernamePasswordAuthenticationToken(
				new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestBeans {

		@Bean
		ChatAccessGuard chatAccessGuard() {
			return Mockito.mock(ChatAccessGuard.class);
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
		ChatWebSocketHandler chatWebSocketHandler() {
			return Mockito.mock(ChatWebSocketHandler.class);
		}
	}
}
