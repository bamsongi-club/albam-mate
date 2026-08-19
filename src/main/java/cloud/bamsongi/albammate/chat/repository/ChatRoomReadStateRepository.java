package cloud.bamsongi.albammate.chat.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.chat.entity.ChatRoomReadState;
import cloud.bamsongi.albammate.chat.entity.ChatRoomReadStateId;

public interface ChatRoomReadStateRepository extends JpaRepository<ChatRoomReadState, ChatRoomReadStateId> {

	/**
	 * 사용자×채팅방 커서를 {@code GREATEST(기존값, upToMessageId)}로만 전진시키는 UPSERT다. 행이 없으면 새로
	 * 만들고, 이미 더 큰 값이면 {@code last_read_message_id}를 후퇴시키지 않되 {@code updated_at}은 커서가 실제로
	 * 전진했을 때만 갱신한다. ANSI {@code MERGE}는 H2·PostgreSQL 모두에서 같은 결과를 내지만,
	 * PostgreSQL 고유 동작(GREATEST·동시 갱신) 검증은 {@code postgresTest}가 별도로 확인한다.
	 *
	 * <p>PostgreSQL {@code MERGE}는 {@code INSERT ... ON CONFLICT}와 달리 동시 최초 삽입 충돌을 스스로
	 * 흡수하지 않고 primary key 위반 예외를 던진다(ADR-0079). {@code REQUIRES_NEW}로 독립된 트랜잭션에서
	 * 실행해 실패해도 호출부({@code ChatRoomReadService})가 예외를 잡아 재시도할 수 있게 한다.
	 */
	@Modifying
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	@Query(value = """
		MERGE INTO chat_room_read_states t
		USING (VALUES (
		    CAST(:userId AS BIGINT), CAST(:chatRoomId AS BIGINT), CAST(:upToMessageId AS BIGINT),
		    CAST(:now AS TIMESTAMP WITH TIME ZONE))) AS src (user_id, chat_room_id, last_read_message_id, updated_at)
		ON t.user_id = src.user_id AND t.chat_room_id = src.chat_room_id
		WHEN MATCHED THEN UPDATE SET
		    last_read_message_id = GREATEST(t.last_read_message_id, src.last_read_message_id),
		    updated_at = CASE WHEN src.last_read_message_id > t.last_read_message_id THEN src.updated_at ELSE t.updated_at END
		WHEN NOT MATCHED THEN INSERT (user_id, chat_room_id, last_read_message_id, updated_at)
		    VALUES (src.user_id, src.chat_room_id, src.last_read_message_id, src.updated_at)
		""", nativeQuery = true)
	void advanceCursor(
		@Param("userId")
		long userId,
		@Param("chatRoomId")
		long chatRoomId,
		@Param("upToMessageId")
		long upToMessageId,
		@Param("now")
		Instant now);
}
