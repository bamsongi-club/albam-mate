package cloud.bamsongi.albammate.chat.retention;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

/** 한 만료 방을 ID 순서 chunk로 지우고, 비었을 때만 완료 시각을 기록한다. */
@Service
class ChatMessageRetentionRoomProcessor {

	private final ChatMessageRetentionStore store;
	private final ChatMessageRetentionChunkExecutor chunkExecutor;
	private final ChatMessageRetentionCompletionExecutor completionExecutor;
	private final ChatMessageRetentionProperties properties;

	ChatMessageRetentionRoomProcessor(
		ChatMessageRetentionStore store,
		ChatMessageRetentionChunkExecutor chunkExecutor,
		ChatMessageRetentionCompletionExecutor completionExecutor,
		ChatMessageRetentionProperties properties) {
		this.store = Objects.requireNonNull(store, "store");
		this.chunkExecutor = Objects.requireNonNull(chunkExecutor, "chunkExecutor");
		this.completionExecutor = Objects.requireNonNull(completionExecutor, "completionExecutor");
		this.properties = Objects.requireNonNull(properties, "properties");
	}

	RoomProcessResult process(ChatMessageRetentionStore.DueChatRoom dueChatRoom, Instant completedAt) {
		int deletedMessageCount = 0;
		while (true) {
			List<Long> messageIds = store.findNextMessageIds(dueChatRoom.chatRoomId(),
				properties.getMessageChunkSize());
			if (messageIds.isEmpty()) {
				boolean completed = completionExecutor.markCompleted(dueChatRoom.chatRoomId(), completedAt);
				return new RoomProcessResult(completed, deletedMessageCount);
			}
			deletedMessageCount += chunkExecutor.deleteChunk(dueChatRoom.chatRoomId(), messageIds);
		}
	}

	record RoomProcessResult(boolean completed, int deletedMessageCount) {
	}
}
