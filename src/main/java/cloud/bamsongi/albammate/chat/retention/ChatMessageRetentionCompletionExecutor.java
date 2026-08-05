package cloud.bamsongi.albammate.chat.retention;

import java.time.Instant;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 모든 메시지가 없다는 최신 조건에서만 보관 완료를 별도 트랜잭션으로 기록한다. */
@Service
class ChatMessageRetentionCompletionExecutor {

	private final ChatMessageRetentionStore store;

	ChatMessageRetentionCompletionExecutor(ChatMessageRetentionStore store) {
		this.store = Objects.requireNonNull(store, "store");
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	boolean markCompleted(long chatRoomId, Instant completedAt) {
		return store.markMessagesPurgedIfEmpty(chatRoomId, completedAt);
	}
}
