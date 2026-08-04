package cloud.bamsongi.albammate.chat.retention;

import java.time.Clock;
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
	private final Clock clock;

	ChatMessageRetentionRoomProcessor(
		ChatMessageRetentionStore store,
		ChatMessageRetentionChunkExecutor chunkExecutor,
		ChatMessageRetentionCompletionExecutor completionExecutor,
		ChatMessageRetentionProperties properties,
		Clock clock) {
		this.store = Objects.requireNonNull(store, "store");
		this.chunkExecutor = Objects.requireNonNull(chunkExecutor, "chunkExecutor");
		this.completionExecutor = Objects.requireNonNull(completionExecutor, "completionExecutor");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	/**
	 * 각 chunk를 시작하기 전에 실행 상한을 확인해, 느린 단일 방 처리가 잠금 임대를 넘기지 않도록 한다.
	 * 상한에 도달하면 완료를 기록하지 않고 중단하며 남은 메시지는 다음 잠금 구간이 이어받는다.
	 */
	RoomProcessResult process(
		ChatMessageRetentionStore.DueChatRoom dueChatRoom,
		Instant completedAt,
		int maximumMessageCandidateCount,
		Instant runDeadline) {
		int deletedMessageCount = 0;
		int candidateMessageCount = 0;
		while (candidateMessageCount < maximumMessageCandidateCount) {
			if (!Instant.now(clock).isBefore(runDeadline)) {
				return new RoomProcessResult(false, deletedMessageCount, candidateMessageCount, false, true);
			}
			int chunkSize = Math.min(properties.getMessageChunkSize(),
				maximumMessageCandidateCount - candidateMessageCount);
			List<Long> messageIds = store.findNextMessageIds(dueChatRoom.chatRoomId(),
				chunkSize);
			if (messageIds.isEmpty()) {
				boolean completed = completionExecutor.markCompleted(dueChatRoom.chatRoomId(), completedAt);
				return new RoomProcessResult(completed, deletedMessageCount, candidateMessageCount, false, false);
			}
			candidateMessageCount += messageIds.size();
			try {
				deletedMessageCount += chunkExecutor.deleteChunk(dueChatRoom.chatRoomId(), messageIds);
			} catch (RuntimeException exception) {
				return new RoomProcessResult(false, deletedMessageCount, candidateMessageCount, true, false);
			}
		}
		return new RoomProcessResult(false, deletedMessageCount, candidateMessageCount, false, false);
	}

	record RoomProcessResult(
		boolean completed,
		int deletedMessageCount,
		int candidateMessageCount,
		boolean failed,
		boolean deadlineReached) {
	}
}
