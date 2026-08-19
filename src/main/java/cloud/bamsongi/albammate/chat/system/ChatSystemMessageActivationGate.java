package cloud.bamsongi.albammate.chat.system;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** SYSTEM 메시지 저장 여부를 결정하는 고정 단일 행 전역 gate다. 행 자체는 expand migration이 생성한다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "chat_system_message_activation")
public class ChatSystemMessageActivationGate {

	@Id
	@Column(name = "gate_name")
	private String gateName;

	@Column(name = "enabled_at")
	private Instant enabledAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
