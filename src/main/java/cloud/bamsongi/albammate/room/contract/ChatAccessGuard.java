package cloud.bamsongi.albammate.room.contract;

import java.util.function.Supplier;

/** 현재 사용자와 ROOM 상태를 기준으로 채팅 접근을 검증하는 공개 계약이다. */
public interface ChatAccessGuard {

	/**
	 * ROOM 접근 판정과 실제 채팅 동작을 하나의 트랜잭션으로 실행한다.
	 *
	 * @param currentUserId 인증된 현재 사용자 ID
	 * @param roomId 접근할 방 ID
	 * @param chatOperation 접근이 허용된 뒤 ROOM 잠금을 유지한 채 실행할 채팅 동작
	 * @param <T> 채팅 동작 결과 타입
	 * @return 채팅 동작 결과
	 */
	<T> T executeWithAccess(long currentUserId, long roomId, Supplier<T> chatOperation);
}
