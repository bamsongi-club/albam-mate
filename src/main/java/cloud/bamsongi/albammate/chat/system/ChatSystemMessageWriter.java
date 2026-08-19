package cloud.bamsongi.albammate.chat.system;

import java.util.Objects;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.entity.ChatSystemEventKey;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.room.contract.RoomParticipantChanged;

/**
 * ROOM 참가 변경 사실을 원인 트랜잭션 안에서 SYSTEM 안내 행으로 반영한다.
 *
 * <p>{@code @EventListener}(동기)로 참가 트랜잭션과 같은 트랜잭션에서 실행되며, gate가 비활성이거나 대상 채팅방이
 * 없으면 안내를 저장하지 않는다. 안내 문장은 저장하지 않고 읽기 시점에 조립한다.
 */
@Component
public class ChatSystemMessageWriter {

	static final String GATE_NAME = "chat-system-message";

	private final ChatSystemMessageActivationGateRepository gateRepository;
	private final ChatRoomRepository chatRoomRepository;
	private final ChatMessageRepository chatMessageRepository;

	public ChatSystemMessageWriter(
		ChatSystemMessageActivationGateRepository gateRepository,
		ChatRoomRepository chatRoomRepository,
		ChatMessageRepository chatMessageRepository) {
		this.gateRepository = Objects.requireNonNull(gateRepository, "gateRepository");
		this.chatRoomRepository = Objects.requireNonNull(chatRoomRepository, "chatRoomRepository");
		this.chatMessageRepository = Objects.requireNonNull(chatMessageRepository, "chatMessageRepository");
	}

	@EventListener
	public void writeSystemMessage(RoomParticipantChanged event) {
		if (!isActiveNow()) {
			return;
		}

		ChatRoom chatRoom = chatRoomRepository.findByRoomIdForMessageAppend(event.roomId()).orElse(null);
		if (chatRoom == null) {
			return;
		}

		ChatSystemEventKey eventKey = toEventKey(event.kind());
		chatMessageRepository.save(
			ChatMessage.createSystemMessage(chatRoom.getId(), eventKey, event.subjectUserId(), event.occurredAt()));
	}

	private boolean isActiveNow() {
		return gateRepository.isActiveNow(GATE_NAME).orElse(false);
	}

	private ChatSystemEventKey toEventKey(RoomParticipantChanged.Kind kind) {
		return switch (kind) {
			case ENTERED -> ChatSystemEventKey.PARTICIPANT_ENTERED;
			case LEFT -> ChatSystemEventKey.PARTICIPANT_LEFT;
		};
	}
}
