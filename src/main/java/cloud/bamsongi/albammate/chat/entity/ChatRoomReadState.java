package cloud.bamsongi.albammate.chat.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자×채팅방마다 어디까지 읽었는지 나타내는 커서 하나만 보관하는 읽기 모델이다.
 *
 * <p>쓰기는 {@code ChatRoomReadStateRepository}의 GREATEST 기반 UPSERT native query로만 이뤄지며, 이 Entity는
 * 갱신 결과를 응답으로 조립하기 위한 조회 전용으로 사용한다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "chat_room_read_states")
public class ChatRoomReadState {

	@EmbeddedId
	private ChatRoomReadStateId id;

	@Column(name = "last_read_message_id", nullable = false)
	private Long lastReadMessageId;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
