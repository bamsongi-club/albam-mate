package cloud.bamsongi.albammate.chat.match.entity;

import cloud.bamsongi.albammate.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "match_chat_rooms")
public class MatchChatRoom extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "party_id", nullable = false)
	private Long partyId;

	/** partyId별로 하나만 존재해야 하는 MATCH 채팅방을 만든다. 멱등 저장은 호출자 repository가 소유한다. */
	public static MatchChatRoom of(long partyId) {
		MatchChatRoom room = new MatchChatRoom();
		room.partyId = partyId;
		return room;
	}
}
