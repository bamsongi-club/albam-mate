package cloud.bamsongi.albammate.chat.repository;

import java.time.OffsetDateTime;

/**
 * 방별 마지막 메시지 배치 조회의 native query 결과 projection이다. TIMESTAMP WITH TIME ZONE 컬럼은
 * {@code OffsetDateTime}으로 매핑되며, 호출부가 {@code Instant}로 변환한다.
 */
public interface ChatRoomLastMessageRow {

	Long getRoomId();

	String getContent();

	OffsetDateTime getCreatedAt();
}
