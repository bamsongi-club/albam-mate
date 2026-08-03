package cloud.bamsongi.albammate.room.contract;

/** 현재 사용자와 ROOM 상태를 기준으로 채팅 접근을 검증하는 공개 계약이다. */
public interface ChatAccessGuard {

	void checkAccess(long currentUserId, long roomId);
}
