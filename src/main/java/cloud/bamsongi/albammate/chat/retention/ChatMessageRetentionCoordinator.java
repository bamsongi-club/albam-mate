package cloud.bamsongi.albammate.chat.retention;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/** 잠금을 얻은 실행에서 제한된 만료 방을 순서대로 처리하고 결과만 관찰한다. */
@Service
@Slf4j
class ChatMessageRetentionCoordinator {

	private final ChatMessageRetentionStore store;
	private final ChatMessageRetentionRoomProcessor roomProcessor;
	private final ChatMessageRetentionProperties properties;
	private final ChatMessageRetentionMetrics metrics;
	private final Clock clock;

	ChatMessageRetentionCoordinator(
		ChatMessageRetentionStore store,
		ChatMessageRetentionRoomProcessor roomProcessor,
		ChatMessageRetentionProperties properties,
		ChatMessageRetentionMetrics metrics,
		Clock clock) {
		this.store = Objects.requireNonNull(store, "store");
		this.roomProcessor = Objects.requireNonNull(roomProcessor, "roomProcessor");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.metrics = Objects.requireNonNull(metrics, "metrics");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	RetentionRunSummary purgeExpiredMessages() {
		long startedAtNanos = System.nanoTime();
		Instant referenceTime = Instant.now(clock);
		Instant runDeadline = referenceTime.plus(properties.getMaxRunDuration());
		int purgedRoomCount = 0;
		int deletedMessageCount = 0;
		int failureCount = 0;
		long maximumDelayMillis = 0;
		boolean leaseGuardAborted = false;
		ChatMessageRetentionStore.DueChatRoomCursor cursor = null;
		ArrayDeque<ChatMessageRetentionStore.DueChatRoom> pendingChatRooms = new ArrayDeque<>();

		while (true) {
			if (pendingChatRooms.isEmpty()) {
				if (isRunDeadlineReached(runDeadline)) {
					leaseGuardAborted = true;
					break;
				}
				List<ChatMessageRetentionStore.DueChatRoom> dueChatRooms = store.findDueChatRooms(
					referenceTime, cursor, properties.getMaxRoomsPerRun());
				if (dueChatRooms.isEmpty()) {
					break;
				}
				pendingChatRooms.addAll(dueChatRooms);
				cursor = ChatMessageRetentionStore.DueChatRoomCursor.after(dueChatRooms.getLast());
			}
			int remainingMessageCandidateBudget = properties.getMaxMessagesPerRun();
			while (remainingMessageCandidateBudget > 0 && !pendingChatRooms.isEmpty()) {
				if (isRunDeadlineReached(runDeadline)) {
					leaseGuardAborted = true;
					break;
				}
				ChatMessageRetentionStore.DueChatRoom dueChatRoom = pendingChatRooms.removeFirst();
				try {
					ChatMessageRetentionRoomProcessor.RoomProcessResult result = roomProcessor.process(
						dueChatRoom, Instant.now(clock), remainingMessageCandidateBudget, runDeadline);
					remainingMessageCandidateBudget = Math.max(0,
						remainingMessageCandidateBudget - result.candidateMessageCount());
					deletedMessageCount += result.deletedMessageCount();
					if (result.deadlineReached()) {
						leaseGuardAborted = true;
						break;
					}
					if (result.completed()) {
						purgedRoomCount++;
						maximumDelayMillis = Math.max(maximumDelayMillis,
							Duration.between(dueChatRoom.purgeAfter(), referenceTime).toMillis());
						continue;
					}
					if (result.failed()) {
						failureCount++;
						log.atWarn().addKeyValue("event", "chat_message_retention_room_failed")
							.log("chat message retention room failed");
						continue;
					}
					if (result.candidateMessageCount() == 0) {
						failureCount++;
						log.atWarn().addKeyValue("event", "chat_message_retention_room_failed")
							.addKeyValue("reasonCode", "NO_PROGRESS").log("chat message retention room failed");
						continue;
					}
					pendingChatRooms.addFirst(dueChatRoom);
				} catch (RuntimeException exception) {
					failureCount++;
					log.atWarn().addKeyValue("event", "chat_message_retention_room_failed")
						.addKeyValue("exceptionClass", exception.getClass().getSimpleName())
						.log("chat message retention room failed");
				}
			}
			if (leaseGuardAborted) {
				break;
			}
		}

		if (leaseGuardAborted) {
			log.atWarn().addKeyValue("event", "chat_message_retention_lease_guard_aborted")
				.addKeyValue("maxRunDurationMs", properties.getMaxRunDuration().toMillis())
				.addKeyValue("lockAtMostForMs", properties.getLockAtMostFor().toMillis())
				.addKeyValue("purgedRoomCount", purgedRoomCount).addKeyValue("deletedMessageCount", deletedMessageCount)
				.log("chat message retention lease guard aborted");
		}
		RetentionRunSummary summary = new RetentionRunSummary(
			purgedRoomCount,
			deletedMessageCount,
			maximumDelayMillis,
			failureCount,
			Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis(),
			leaseGuardAborted);
		metrics.recordCompleted(summary);
		logSummary(summary);
		return summary;
	}

	/** 반복 batch가 잠금 임대를 넘기지 않도록 남은 작업을 다음 스케줄로 넘길 시점을 판정한다. */
	private boolean isRunDeadlineReached(Instant runDeadline) {
		return !Instant.now(clock).isBefore(runDeadline);
	}

	private void logSummary(RetentionRunSummary summary) {
		if (summary.durationMillis() > properties.getExecutionWarningThreshold().toMillis()) {
			log.atWarn().addKeyValue("event", "chat_message_retention_completed")
				.addKeyValue("purgedRoomCount", summary.purgedRoomCount())
				.addKeyValue("deletedMessageCount", summary.deletedMessageCount())
				.addKeyValue("maximumDelayMs", summary.maximumDelayMillis())
				.addKeyValue("failureCount", summary.failureCount())
				.addKeyValue("durationMs", summary.durationMillis())
				.addKeyValue("warningThresholdMs", properties.getExecutionWarningThreshold().toMillis())
				.log("chat message retention completed");
			return;
		}
		log.atInfo().addKeyValue("event", "chat_message_retention_completed")
			.addKeyValue("purgedRoomCount", summary.purgedRoomCount())
			.addKeyValue("deletedMessageCount", summary.deletedMessageCount())
			.addKeyValue("maximumDelayMs", summary.maximumDelayMillis())
			.addKeyValue("failureCount", summary.failureCount())
			.addKeyValue("durationMs", summary.durationMillis()).log("chat message retention completed");
	}

	record RetentionRunSummary(
		int purgedRoomCount,
		int deletedMessageCount,
		long maximumDelayMillis,
		int failureCount,
		long durationMillis,
		boolean leaseGuardAborted) {
	}
}
