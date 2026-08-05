package cloud.bamsongi.albammate.chat.retention;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 한 메시지 chunk만 독립 트랜잭션으로 지워 다른 방·chunk 성공을 보존한다. */
@Service
class ChatMessageRetentionChunkExecutor {

	private final ChatMessageRetentionStore store;

	ChatMessageRetentionChunkExecutor(ChatMessageRetentionStore store) {
		this.store = Objects.requireNonNull(store, "store");
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	int deleteChunk(long chatRoomId, List<Long> messageIds) {
		return store.deleteMessageChunk(chatRoomId, messageIds);
	}
}
