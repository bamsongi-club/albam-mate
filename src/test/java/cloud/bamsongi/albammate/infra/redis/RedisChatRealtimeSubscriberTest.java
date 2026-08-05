package cloud.bamsongi.albammate.infra.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;

import cloud.bamsongi.albammate.chat.contract.ChatRealtimeSignalGateway;
import cloud.bamsongi.albammate.chat.contract.MessageCommitted;

class RedisChatRealtimeSubscriberTest {

	@Test
	void 정상_신호를_커밋_사실로_디코드한다() {
		assertEquals(
			MessageCommitted.messageCreated(7L, 42L),
			RedisChatRealtimeSubscriber.decode("MESSAGE_CREATED:7:42").orElseThrow());
	}

	@Test
	void 형식이_잘못된_신호는_무시한다() {
		assertTrue(RedisChatRealtimeSubscriber.decode("MESSAGE_CREATED:7").isEmpty());
		assertTrue(RedisChatRealtimeSubscriber.decode("MESSAGE_CREATED:not-a-number:42").isEmpty());
		assertTrue(RedisChatRealtimeSubscriber.decode("UNKNOWN:7:42").isEmpty());
		assertTrue(RedisChatRealtimeSubscriber.decode("MESSAGE_CREATED:0:42").isEmpty());
	}

	@Test
	void 정상_신호만_로컬_웹소켓_게이트웨이로_전달한다() {
		ChatRealtimeSignalGateway gateway = mock(ChatRealtimeSignalGateway.class);
		RedisChatRealtimeSubscriber subscriber = new RedisChatRealtimeSubscriber(gateway);
		Message valid = message("MESSAGE_CREATED:7:42");
		Message invalid = message("invalid");

		subscriber.onMessage(valid, null);
		subscriber.onMessage(invalid, null);

		verify(gateway).onMessageCommitted(MessageCommitted.messageCreated(7L, 42L));
		verifyNoMoreInteractions(gateway);
	}

	private Message message(String payload) {
		Message message = mock(Message.class);
		when(message.getBody()).thenReturn(payload.getBytes(StandardCharsets.UTF_8));
		return message;
	}
}
