package cloud.bamsongi.albammate.global.security.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.MapSessionRepository;

import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.security.error.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;

/** T9: 세션 저장소 gate가 채팅 세 경로에만 적용되고, 확인 실패 시 체인을 진행하지 않는지 직접 검증한다. */
class ChatSessionStoreAvailabilityFilterTest {

	private final MapSessionRepository sessionRepository = spy(
		new MapSessionRepository(new ConcurrentHashMap<>()));
	private final SecurityErrorResponseWriter responseWriter = mock(SecurityErrorResponseWriter.class);
	private final ChatSessionStoreAvailabilityFilter filter = new ChatSessionStoreAvailabilityFilter(
		sessionRepository, responseWriter);

	@Test
	void 세션_저장소_조회가_실패하면_메시지_전송_경로를_503으로_거절하고_체인을_진행하지_않는다() throws Exception {
		doThrow(new IllegalStateException("session store unavailable")).when(sessionRepository).findById(any());
		FilterChain chain = mock(FilterChain.class);
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/rooms/1/chat/messages");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		verify(responseWriter).write(response, ErrorCode.SERVICE_UNAVAILABLE);
		verify(chain, never()).doFilter(any(), any());
	}

	@Test
	void 세션_저장소_조회가_실패하면_이력_조회_경로도_503으로_거절한다() throws Exception {
		doThrow(new IllegalStateException("session store unavailable")).when(sessionRepository).findById(any());
		FilterChain chain = mock(FilterChain.class);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rooms/1/chat/messages");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		verify(responseWriter).write(response, ErrorCode.SERVICE_UNAVAILABLE);
		verify(chain, never()).doFilter(any(), any());
	}

	@Test
	void 세션_저장소_조회가_실패하면_WebSocket_handshake_경로도_upgrade_전에_503으로_거절한다() throws Exception {
		doThrow(new IllegalStateException("session store unavailable")).when(sessionRepository).findById(any());
		FilterChain chain = mock(FilterChain.class);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rooms/1/chat/ws");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		verify(responseWriter).write(response, ErrorCode.SERVICE_UNAVAILABLE);
		verify(chain, never()).doFilter(any(), any());
	}

	@Test
	void 세션_저장소가_정상이면_채팅_경로도_체인을_그대로_진행하고_503을_쓰지_않는다() throws Exception {
		FilterChain chain = mock(FilterChain.class);
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/rooms/1/chat/messages");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		verify(chain, times(1)).doFilter(request, response);
		verify(responseWriter, never()).write(any(), any());
	}

	@Test
	void 채팅_경로가_아니면_저장소를_확인하지_않고_체인을_그대로_진행한다() throws Exception {
		doThrow(new IllegalStateException("session store unavailable")).when(sessionRepository).findById(any());
		FilterChain chain = mock(FilterChain.class);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		verify(chain, times(1)).doFilter(request, response);
		verify(responseWriter, never()).write(any(), any());
		verify(sessionRepository, never()).findById(any());
	}

	@Test
	void 세션_저장소_실패_503_응답에는_Retry_After가_없다() throws Exception {
		doThrow(new IllegalStateException("session store unavailable")).when(sessionRepository).findById(any());
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/rooms/1/chat/messages");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, mock(FilterChain.class));

		assertEquals(null, response.getHeader("Retry-After"));
	}

	@Test
	void 세션_저장소_조회가_실패하면_채팅_목록_WebSocket_handshake_경로도_upgrade_전에_503으로_거절한다() throws Exception {
		doThrow(new IllegalStateException("session store unavailable")).when(sessionRepository).findById(any());
		FilterChain chain = mock(FilterChain.class);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me/chat/ws");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		verify(responseWriter).write(response, ErrorCode.SERVICE_UNAVAILABLE);
		verify(chain, never()).doFilter(any(), any());
	}

	@Test
	void 세션_저장소가_정상이면_채팅_목록_WebSocket_경로도_체인을_그대로_진행하고_503을_쓰지_않는다() throws Exception {
		FilterChain chain = mock(FilterChain.class);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me/chat/ws");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		verify(chain, times(1)).doFilter(request, response);
		verify(responseWriter, never()).write(any(), any());
	}

	@Test
	void 채팅_목록_WebSocket_경로_503_응답에는_Retry_After가_없다() throws Exception {
		doThrow(new IllegalStateException("session store unavailable")).when(sessionRepository).findById(any());
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me/chat/ws");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, mock(FilterChain.class));

		assertEquals(null, response.getHeader("Retry-After"));
	}
}
