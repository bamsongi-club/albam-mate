package cloud.bamsongi.albammate.chat.match.contract;

/** 신규 MATCH 채팅 메시지의 사용자·Party 전송량을 함께 예약하고, 저장 롤백 때 예약을 되돌리는 port다. */
public interface MatchChatMessageRateLimiter {

	RateLimitReservation reserve(long userId, long partyId);

	interface RateLimitReservation {

		void release();
	}
}
