package cloud.bamsongi.albammate.chat.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ChatMessageRetentionRoomProcessorTest {

	@Test
	void 만료_방의_메시지를_ID_오름차순_작은_chunk로_지우고_빈_방만_완료한다() {
		ChatMessageRetentionStore store = mock(ChatMessageRetentionStore.class);
		ChatMessageRetentionChunkExecutor chunkExecutor = mock(ChatMessageRetentionChunkExecutor.class);
		ChatMessageRetentionCompletionExecutor completionExecutor = mock(ChatMessageRetentionCompletionExecutor.class);
		ChatMessageRetentionProperties properties = new ChatMessageRetentionProperties();
		properties.setMessageChunkSize(2);
		ChatMessageRetentionRoomProcessor processor = new ChatMessageRetentionRoomProcessor(
			store, chunkExecutor, completionExecutor, properties);
		ChatMessageRetentionStore.DueChatRoom dueChatRoom = new ChatMessageRetentionStore.DueChatRoom(
			7L, Instant.parse("2026-08-01T00:00:00Z"));
		Instant completedAt = Instant.parse("2026-08-02T00:00:00Z");
		when(store.findNextMessageIds(7L, 2)).thenReturn(List.of(10L, 11L), List.of(12L), List.of());
		when(chunkExecutor.deleteChunk(7L, List.of(10L, 11L))).thenReturn(2);
		when(chunkExecutor.deleteChunk(7L, List.of(12L))).thenReturn(1);
		when(completionExecutor.markCompleted(7L, completedAt)).thenReturn(true);

		ChatMessageRetentionRoomProcessor.RoomProcessResult result = processor.process(dueChatRoom, completedAt, 5);

		assertEquals(new ChatMessageRetentionRoomProcessor.RoomProcessResult(true, 3, 3, false), result);
		InOrder inOrder = inOrder(store, chunkExecutor, completionExecutor);
		inOrder.verify(store).findNextMessageIds(7L, 2);
		inOrder.verify(chunkExecutor).deleteChunk(7L, List.of(10L, 11L));
		inOrder.verify(store).findNextMessageIds(7L, 2);
		inOrder.verify(chunkExecutor).deleteChunk(7L, List.of(12L));
		inOrder.verify(store).findNextMessageIds(7L, 2);
		inOrder.verify(completionExecutor).markCompleted(7L, completedAt);
	}

	@Test
	void 메시지_후보_상한에_도달하면_완료_시각을_기록하지_않고_부분_삭제_건수를_반환한다() {
		ChatMessageRetentionStore store = mock(ChatMessageRetentionStore.class);
		ChatMessageRetentionChunkExecutor chunkExecutor = mock(ChatMessageRetentionChunkExecutor.class);
		ChatMessageRetentionCompletionExecutor completionExecutor = mock(ChatMessageRetentionCompletionExecutor.class);
		ChatMessageRetentionProperties properties = new ChatMessageRetentionProperties();
		properties.setMessageChunkSize(2);
		ChatMessageRetentionRoomProcessor processor = new ChatMessageRetentionRoomProcessor(
			store, chunkExecutor, completionExecutor, properties);
		ChatMessageRetentionStore.DueChatRoom dueChatRoom = new ChatMessageRetentionStore.DueChatRoom(
			7L, Instant.parse("2026-08-01T00:00:00Z"));
		Instant completedAt = Instant.parse("2026-08-02T00:00:00Z");
		when(store.findNextMessageIds(7L, 2)).thenReturn(List.of(10L, 11L));
		when(store.findNextMessageIds(7L, 1)).thenReturn(List.of(12L));
		when(chunkExecutor.deleteChunk(7L, List.of(10L, 11L))).thenReturn(2);
		when(chunkExecutor.deleteChunk(7L, List.of(12L))).thenReturn(1);

		ChatMessageRetentionRoomProcessor.RoomProcessResult result = processor.process(dueChatRoom, completedAt, 3);

		assertFalse(result.completed());
		assertEquals(3, result.deletedMessageCount());
		assertEquals(3, result.candidateMessageCount());
		verify(completionExecutor, never()).markCompleted(7L, completedAt);
	}
}
