package cloud.bamsongi.albammate.chat.entity;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Embeddable
public class ChatRoomReadStateId implements Serializable {

	@Column(name = "user_id")
	private Long userId;
	@Column(name = "chat_room_id")
	private Long chatRoomId;

	public ChatRoomReadStateId(long userId, long chatRoomId) {
		this.userId = userId;
		this.chatRoomId = chatRoomId;
	}
}
