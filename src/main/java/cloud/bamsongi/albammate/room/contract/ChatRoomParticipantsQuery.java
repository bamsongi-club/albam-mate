package cloud.bamsongi.albammate.room.contract;

import java.util.List;

/** CHAT-08 사용자 단위 팬아웃이 주어진 방의 현재 관계자 user id 전체를 조회하는 공개 계약이다. */
public interface ChatRoomParticipantsQuery {

	/**
	 * 주어진 {@code roomId}의 현재 주최자와 {@code ACTIVE} 참가자 user id 전체를 반환한다.
	 *
	 * <p>방이 없으면 빈 목록을 반환한다. 이 조회는 접근 판정이 아니라 팬아웃 대상 산출용이며, 단일 사용자의 접근
	 * 여부만 확인하는 {@link ChatAccessGuard#executeWithAccess}와는 별개 계약이다.
	 *
	 * @param roomId 참가자를 조회할 방 ID
	 * @return 주최자·{@code ACTIVE} 참가자 user id 목록(중복 없음)
	 */
	List<Long> findCurrentParticipantUserIds(long roomId);
}
