package cloud.bamsongi.albammate.chat.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.chat.contract.ChatMessageRateLimiter;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.room.contract.ChatAccessGuard;
import cloud.bamsongi.albammate.user.contract.UserQuery;

class ChatMessageCommandServiceUnitTest {

	@Test
	void rate_limit_reservation_release는_여러번_호출해도_한번만_실행한다() {
		ChatMessageRateLimiter.RateLimitReservation reservation = mock(
			ChatMessageRateLimiter.RateLimitReservation.class);
		ChatMessageCommandService service = new ChatMessageCommandService(
			mock(ChatAccessGuard.class),
			mock(ChatRoomRepository.class),
			mock(ChatMessageRepository.class),
			mock(UserQuery.class),
			mock(ChatMessageRateLimiter.class),
			mock(org.springframework.context.ApplicationEventPublisher.class),
			Clock.systemUTC(),
			new ChatMessageLimitProperties());

		Runnable releaseOnce = ReflectionTestUtils.invokeMethod(service, "releaseOnce", reservation);
		releaseOnce.run();
		releaseOnce.run();

		verify(reservation).release();
	}
}
