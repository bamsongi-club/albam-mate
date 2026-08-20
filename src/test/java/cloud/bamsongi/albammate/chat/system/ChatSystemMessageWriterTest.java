package cloud.bamsongi.albammate.chat.system;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import cloud.bamsongi.albammate.chat.contract.MessageCommitted;
import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.room.contract.RoomParticipantChanged;

class ChatSystemMessageWriterTest {

	private static final long ROOM_ID = 42L;
	private static final long CHAT_ROOM_ID = 7L;
	private static final long SUBJECT_USER_ID = 100L;
	private static final long SAVED_MESSAGE_ID = 555L;
	private static final Instant OCCURRED_AT = Instant.parse("2026-08-04T00:00:00Z");

	@Test
	void 참가_SYSTEM_메시지가_저장되면_커밋_신호가_정확히_한번_발행된다() {
		ChatMessage saved = mock(ChatMessage.class);
		when(saved.getId()).thenReturn(SAVED_MESSAGE_ID);
		ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
		ChatSystemMessageWriter writer = writerActivatedWithChatRoom(saved, eventPublisher);

		writer.writeSystemMessage(
			new RoomParticipantChanged(ROOM_ID, SUBJECT_USER_ID, RoomParticipantChanged.Kind.ENTERED, OCCURRED_AT));

		verify(eventPublisher, times(1)).publishEvent(MessageCommitted.messageCreated(ROOM_ID, SAVED_MESSAGE_ID));
	}

	@Test
	void 퇴장_SYSTEM_메시지가_저장되면_커밋_신호가_정확히_한번_발행된다() {
		ChatMessage saved = mock(ChatMessage.class);
		when(saved.getId()).thenReturn(SAVED_MESSAGE_ID);
		ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
		ChatSystemMessageWriter writer = writerActivatedWithChatRoom(saved, eventPublisher);

		writer.writeSystemMessage(
			new RoomParticipantChanged(ROOM_ID, SUBJECT_USER_ID, RoomParticipantChanged.Kind.LEFT, OCCURRED_AT));

		verify(eventPublisher, times(1)).publishEvent(MessageCommitted.messageCreated(ROOM_ID, SAVED_MESSAGE_ID));
	}

	@Test
	void gate가_비활성이면_저장도_커밋_신호_발행도_하지_않는다() {
		ChatSystemMessageActivationGateRepository gateRepository = mock(
			ChatSystemMessageActivationGateRepository.class);
		when(gateRepository.isActiveNow(ChatSystemMessageWriter.GATE_NAME)).thenReturn(Optional.of(false));
		ChatRoomRepository chatRoomRepository = mock(ChatRoomRepository.class);
		ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
		ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
		ChatSystemMessageWriter writer = new ChatSystemMessageWriter(
			gateRepository, chatRoomRepository, chatMessageRepository, eventPublisher);

		writer.writeSystemMessage(
			new RoomParticipantChanged(ROOM_ID, SUBJECT_USER_ID, RoomParticipantChanged.Kind.ENTERED, OCCURRED_AT));

		verify(chatRoomRepository, never()).findByRoomIdForMessageAppend(anyLong());
		verify(chatMessageRepository, never()).save(any());
		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	void 대상_채팅방이_없으면_저장도_커밋_신호_발행도_하지_않는다() {
		ChatSystemMessageActivationGateRepository gateRepository = mock(
			ChatSystemMessageActivationGateRepository.class);
		when(gateRepository.isActiveNow(ChatSystemMessageWriter.GATE_NAME)).thenReturn(Optional.of(true));
		ChatRoomRepository chatRoomRepository = mock(ChatRoomRepository.class);
		when(chatRoomRepository.findByRoomIdForMessageAppend(ROOM_ID)).thenReturn(Optional.empty());
		ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
		ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
		ChatSystemMessageWriter writer = new ChatSystemMessageWriter(
			gateRepository, chatRoomRepository, chatMessageRepository, eventPublisher);

		writer.writeSystemMessage(
			new RoomParticipantChanged(ROOM_ID, SUBJECT_USER_ID, RoomParticipantChanged.Kind.ENTERED, OCCURRED_AT));

		verify(chatMessageRepository, never()).save(any());
		verify(eventPublisher, never()).publishEvent(any());
	}

	private ChatSystemMessageWriter writerActivatedWithChatRoom(
		ChatMessage saved, ApplicationEventPublisher eventPublisher) {
		ChatSystemMessageActivationGateRepository gateRepository = mock(
			ChatSystemMessageActivationGateRepository.class);
		when(gateRepository.isActiveNow(ChatSystemMessageWriter.GATE_NAME)).thenReturn(Optional.of(true));
		ChatRoomRepository chatRoomRepository = mock(ChatRoomRepository.class);
		ChatRoom chatRoom = mock(ChatRoom.class);
		when(chatRoom.getId()).thenReturn(CHAT_ROOM_ID);
		when(chatRoomRepository.findByRoomIdForMessageAppend(ROOM_ID)).thenReturn(Optional.of(chatRoom));
		ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
		when(chatMessageRepository.save(any(ChatMessage.class))).thenReturn(saved);
		return new ChatSystemMessageWriter(gateRepository, chatRoomRepository, chatMessageRepository, eventPublisher);
	}
}
