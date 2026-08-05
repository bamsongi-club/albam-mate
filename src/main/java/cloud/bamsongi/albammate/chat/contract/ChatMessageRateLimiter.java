package cloud.bamsongi.albammate.chat.contract;

/** 신규 채팅 메시지의 사용자·방 전송량을 함께 예약하고, 저장 롤백 때 예약을 되돌리는 port다. */
public interface ChatMessageRateLimiter {

	RateLimitReservation reserve(long userId, long roomId);

	interface RateLimitReservation {

		void release();
	}
}
