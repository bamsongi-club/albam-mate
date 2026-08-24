package cloud.bamsongi.albammate.chat.contract;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** room이 채팅 목록을 조립할 때 방마다 마지막 메시지 미리보기·미읽음 개수를 배치로 얻는 공개 계약이다. */
public interface ChatRoomPreviewQuery {

	/**
	 * 요청한 roomId(ROOM 공개 ID) 집합과 조회자 기준으로 방마다 미리보기를 배치로 반환한다. 방 개수만큼
	 * 반복 질의하지 않는다. 결과 Map에 없는 roomId는 메시지가 없는 방으로 취급한다({@link ChatRoomPreview#EMPTY}).
	 */
	Map<Long, ChatRoomPreview> findPreviews(long currentUserId, Set<Long> roomIds);

	/** 채팅방 하나의 미리보기 값이다. */
	record ChatRoomPreview(String lastMessagePreview, Instant lastMessageAt, int unreadCount) {

		public static final ChatRoomPreview EMPTY = new ChatRoomPreview(null, null, 0);
	}
}
