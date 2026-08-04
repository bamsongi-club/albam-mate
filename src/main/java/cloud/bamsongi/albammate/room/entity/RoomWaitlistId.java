package cloud.bamsongi.albammate.room.entity;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** 같은 ROOM과 사용자의 최신 대기 관계를 식별하는 복합 키다. */
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RoomWaitlistId implements Serializable {

	private static final long serialVersionUID = 1L;

	@Column(name = "room_id")
	private Long roomId;

	@Column(name = "user_id")
	private Long userId;
}
