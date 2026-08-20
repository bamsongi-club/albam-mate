package cloud.bamsongi.albammate.chat.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.room.contract.RoomCreated;
import cloud.bamsongi.albammate.room.contract.RoomTerminalStateReached;
import lombok.RequiredArgsConstructor;

/** ROOM 계약 이벤트를 같은 트랜잭션에서 채팅방 수명 주기로 반영한다. */
@Component
@RequiredArgsConstructor
public class ChatRoomLifecycleListener {

	private final ChatRoomRepository chatRoomRepository;

	@EventListener
	public void createChatRoom(RoomCreated event) {
		ChatRoom chatRoom = chatRoomRepository.save(ChatRoom.create(event.roomId()));
		event.completeChatRoom(chatRoom.getId());
	}

	@EventListener
	public void schedulePurge(RoomTerminalStateReached event) {
		chatRoomRepository
			.findByRoomId(event.roomId())
			.ifPresent(chatRoom -> chatRoom.schedulePurgeAfter(event.reachedAt()));
	}
}
