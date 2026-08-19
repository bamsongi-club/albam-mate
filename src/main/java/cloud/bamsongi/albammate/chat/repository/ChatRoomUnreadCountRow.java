package cloud.bamsongi.albammate.chat.repository;

/** 방별 미읽음 개수 배치 조회의 native query 결과 projection이다. */
public interface ChatRoomUnreadCountRow {

	Long getRoomId();

	Long getUnreadCount();
}
