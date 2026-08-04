package cloud.bamsongi.albammate.chat.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
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

		ChatMessageRetentionRoomProcessor.RoomProcessResult result = processor.process(dueChatRoom, completedAt);

		assertEquals(new ChatMessageRetentionRoomProcessor.RoomProcessResult(true, 3), result);
		InOrder inOrder = inOrder(store, chunkExecutor, completionExecutor);
		inOrder.verify(store).findNextMessageIds(7L, 2);
		inOrder.verify(chunkExecutor).deleteChunk(7L, List.of(10L, 11L));
		inOrder.verify(store).findNextMessageIds(7L, 2);
		inOrder.verify(chunkExecutor).deleteChunk(7L, List.of(12L));
		inOrder.verify(store).findNextMessageIds(7L, 2);
		inOrder.verify(completionExecutor).markCompleted(7L, completedAt);
	}
}
