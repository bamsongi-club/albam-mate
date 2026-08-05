package cloud.bamsongi.albammate.chat.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ChatMessageRetentionRoomProcessorTest {

	private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");
	private static final Instant FAR_DEADLINE = NOW.plusSeconds(600);

	@Test
	void 만료_방의_메시지를_ID_오름차순_작은_chunk로_지우고_빈_방만_완료한다() {
		ChatMessageRetentionStore store = mock(ChatMessageRetentionStore.class);
		ChatMessageRetentionChunkExecutor chunkExecutor = mock(ChatMessageRetentionChunkExecutor.class);
		ChatMessageRetentionCompletionExecutor completionExecutor = mock(ChatMessageRetentionCompletionExecutor.class);
		ChatMessageRetentionProperties properties = new ChatMessageRetentionProperties();
		properties.setMessageChunkSize(2);
		ChatMessageRetentionRoomProcessor processor = new ChatMessageRetentionRoomProcessor(
			store, chunkExecutor, completionExecutor, properties, Clock.fixed(NOW, ZoneOffset.UTC));
		ChatMessageRetentionStore.DueChatRoom dueChatRoom = new ChatMessageRetentionStore.DueChatRoom(
			7L, Instant.parse("2026-08-01T00:00:00Z"));
		when(store.findNextMessageIds(7L, 2)).thenReturn(List.of(10L, 11L), List.of(12L), List.of());
		when(chunkExecutor.deleteChunk(7L, List.of(10L, 11L))).thenReturn(2);
		when(chunkExecutor.deleteChunk(7L, List.of(12L))).thenReturn(1);
		when(completionExecutor.markCompleted(7L, NOW)).thenReturn(true);

		ChatMessageRetentionRoomProcessor.RoomProcessResult result = processor.process(
			dueChatRoom, NOW, 5, FAR_DEADLINE);

		assertEquals(
			new ChatMessageRetentionRoomProcessor.RoomProcessResult(true, 3, 3, false, false), result);
		InOrder inOrder = inOrder(store, chunkExecutor, completionExecutor);
		inOrder.verify(store).findNextMessageIds(7L, 2);
		inOrder.verify(chunkExecutor).deleteChunk(7L, List.of(10L, 11L));
		inOrder.verify(store).findNextMessageIds(7L, 2);
		inOrder.verify(chunkExecutor).deleteChunk(7L, List.of(12L));
		inOrder.verify(store).findNextMessageIds(7L, 2);
		inOrder.verify(completionExecutor).markCompleted(7L, NOW);
	}

	@Test
	void 메시지_후보_상한에_도달하면_완료_시각을_기록하지_않고_부분_삭제_건수를_반환한다() {
		ChatMessageRetentionStore store = mock(ChatMessageRetentionStore.class);
		ChatMessageRetentionChunkExecutor chunkExecutor = mock(ChatMessageRetentionChunkExecutor.class);
		ChatMessageRetentionCompletionExecutor completionExecutor = mock(ChatMessageRetentionCompletionExecutor.class);
		ChatMessageRetentionProperties properties = new ChatMessageRetentionProperties();
		properties.setMessageChunkSize(2);
		ChatMessageRetentionRoomProcessor processor = new ChatMessageRetentionRoomProcessor(
			store, chunkExecutor, completionExecutor, properties, Clock.fixed(NOW, ZoneOffset.UTC));
		ChatMessageRetentionStore.DueChatRoom dueChatRoom = new ChatMessageRetentionStore.DueChatRoom(
			7L, Instant.parse("2026-08-01T00:00:00Z"));
		when(store.findNextMessageIds(7L, 2)).thenReturn(List.of(10L, 11L));
		when(store.findNextMessageIds(7L, 1)).thenReturn(List.of(12L));
		when(chunkExecutor.deleteChunk(7L, List.of(10L, 11L))).thenReturn(2);
		when(chunkExecutor.deleteChunk(7L, List.of(12L))).thenReturn(1);

		ChatMessageRetentionRoomProcessor.RoomProcessResult result = processor.process(
			dueChatRoom, NOW, 3, FAR_DEADLINE);

		assertFalse(result.completed());
		assertFalse(result.deadlineReached());
		assertEquals(3, result.deletedMessageCount());
		assertEquals(3, result.candidateMessageCount());
		verify(completionExecutor, never()).markCompleted(7L, NOW);
	}

	@Test
	void 실행_상한이_이미_지났으면_chunk를_조회하지_않고_중단을_보고한다() {
		ChatMessageRetentionStore store = mock(ChatMessageRetentionStore.class);
		ChatMessageRetentionChunkExecutor chunkExecutor = mock(ChatMessageRetentionChunkExecutor.class);
		ChatMessageRetentionCompletionExecutor completionExecutor = mock(ChatMessageRetentionCompletionExecutor.class);
		ChatMessageRetentionRoomProcessor processor = new ChatMessageRetentionRoomProcessor(
			store, chunkExecutor, completionExecutor, new ChatMessageRetentionProperties(),
			Clock.fixed(NOW, ZoneOffset.UTC));
		ChatMessageRetentionStore.DueChatRoom dueChatRoom = new ChatMessageRetentionStore.DueChatRoom(
			7L, Instant.parse("2026-08-01T00:00:00Z"));

		ChatMessageRetentionRoomProcessor.RoomProcessResult result = processor.process(
			dueChatRoom, NOW, 5_000, NOW);

		assertTrue(result.deadlineReached());
		assertFalse(result.completed());
		assertFalse(result.failed());
		assertEquals(0, result.deletedMessageCount());
		verifyNoInteractions(chunkExecutor, completionExecutor);
		verify(store, never()).findNextMessageIds(anyLong(), anyInt());
	}

	@Test
	void 여러_chunk_처리_중_실행_상한에_도달하면_남은_chunk를_처리하지_않는다() {
		ChatMessageRetentionStore store = mock(ChatMessageRetentionStore.class);
		ChatMessageRetentionChunkExecutor chunkExecutor = mock(ChatMessageRetentionChunkExecutor.class);
		ChatMessageRetentionCompletionExecutor completionExecutor = mock(ChatMessageRetentionCompletionExecutor.class);
		ChatMessageRetentionProperties properties = new ChatMessageRetentionProperties();
		properties.setMessageChunkSize(2);
		ChatMessageRetentionRoomProcessor processor = new ChatMessageRetentionRoomProcessor(
			store, chunkExecutor, completionExecutor, properties, steppingClock(Duration.ofSeconds(1)));
		ChatMessageRetentionStore.DueChatRoom dueChatRoom = new ChatMessageRetentionStore.DueChatRoom(
			7L, Instant.parse("2026-08-01T00:00:00Z"));
		when(store.findNextMessageIds(7L, 2)).thenReturn(List.of(10L, 11L));
		when(chunkExecutor.deleteChunk(7L, List.of(10L, 11L))).thenReturn(2);

		ChatMessageRetentionRoomProcessor.RoomProcessResult result = processor.process(
			dueChatRoom, NOW, 100, NOW.plusSeconds(1));

		assertTrue(result.deadlineReached());
		assertFalse(result.completed());
		assertEquals(2, result.deletedMessageCount());
		assertEquals(2, result.candidateMessageCount());
		verify(store).findNextMessageIds(7L, 2);
		verifyNoInteractions(completionExecutor);
	}

	/** 고정 시각에서 호출마다 같은 폭으로만 진행해 상한 도달 시점을 결정적으로 만든다. */
	private Clock steppingClock(Duration step) {
		return new Clock() {

			private Instant current = NOW;

			@Override
			public ZoneOffset getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(java.time.ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				Instant reading = current;
				current = current.plus(step);
				return reading;
			}
		};
	}
}
