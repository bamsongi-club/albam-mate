package cloud.bamsongi.albammate.chat.retention;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
		int purgedRoomCount = 0;
		int deletedMessageCount = 0;
		int failureCount = 0;
		long maximumDelayMillis = 0;
		Set<Long> failedRoomIds = new HashSet<>();

		while (true) {
			List<ChatMessageRetentionStore.DueChatRoom> dueChatRooms = failedRoomIds.isEmpty()
				? store.findDueChatRooms(referenceTime, properties.getMaxRoomsPerRun())
				: store.findDueChatRooms(referenceTime, properties.getMaxRoomsPerRun(), failedRoomIds);
			if (dueChatRooms.isEmpty()) {
				break;
			}
			boolean madeProgress = false;
			for (ChatMessageRetentionStore.DueChatRoom dueChatRoom : dueChatRooms) {
				try {
					ChatMessageRetentionRoomProcessor.RoomProcessResult result = roomProcessor.process(
						dueChatRoom, Instant.now(clock), properties.getMaxMessagesPerRun());
					madeProgress |= result.completed() || result.candidateMessageCount() > 0;
					deletedMessageCount += result.deletedMessageCount();
					if (result.completed()) {
						purgedRoomCount++;
						maximumDelayMillis = Math.max(maximumDelayMillis,
							Duration.between(dueChatRoom.purgeAfter(), referenceTime).toMillis());
					}
					if (result.failed()) {
						failureCount++;
						failedRoomIds.add(dueChatRoom.chatRoomId());
						log.warn("event=chat_message_retention_room_failed");
					}
				} catch (RuntimeException exception) {
					failureCount++;
					failedRoomIds.add(dueChatRoom.chatRoomId());
					log.warn("event=chat_message_retention_room_failed exceptionClass={}",
						exception.getClass().getSimpleName());
				}
			}
			if (!madeProgress) {
				failedRoomIds.addAll(dueChatRooms.stream()
					.map(ChatMessageRetentionStore.DueChatRoom::chatRoomId)
					.toList());
			}
		}

		RetentionRunSummary summary = new RetentionRunSummary(
			purgedRoomCount,
			deletedMessageCount,
			maximumDelayMillis,
			failureCount,
			Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis());
		metrics.recordCompleted(summary);
		logSummary(summary);
		return summary;
	}

	private void logSummary(RetentionRunSummary summary) {
		if (summary.durationMillis() > properties.getExecutionWarningThreshold().toMillis()) {
			log.warn(
				"event=chat_message_retention_completed purgedRoomCount={} deletedMessageCount={} maximumDelayMs={} "
					+ "failureCount={} durationMs={} warningThresholdMs={}",
				summary.purgedRoomCount(),
				summary.deletedMessageCount(), summary.maximumDelayMillis(), summary.failureCount(),
				summary.durationMillis(),
				properties.getExecutionWarningThreshold().toMillis());
			return;
		}
		log.info("event=chat_message_retention_completed purgedRoomCount={} deletedMessageCount={} maximumDelayMs={} "
			+ "failureCount={} durationMs={}", summary.purgedRoomCount(), summary.deletedMessageCount(),
			summary.maximumDelayMillis(), summary.failureCount(), summary.durationMillis());
	}

	record RetentionRunSummary(
		int purgedRoomCount,
		int deletedMessageCount,
		long maximumDelayMillis,
		int failureCount,
		long durationMillis) {
	}
}
