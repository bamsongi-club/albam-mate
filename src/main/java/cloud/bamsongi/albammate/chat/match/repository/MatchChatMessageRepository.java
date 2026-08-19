package cloud.bamsongi.albammate.chat.match.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import cloud.bamsongi.albammate.chat.match.MatchChatSystemEventKey;
import cloud.bamsongi.albammate.chat.match.entity.MatchChatMessage;

public interface MatchChatMessageRepository extends JpaRepository<MatchChatMessage, Long> {

	Optional<MatchChatMessage> findByMatchChatRoomIdAndSystemEventKey(
		Long matchChatRoomId, MatchChatSystemEventKey systemEventKey);

	/** CHAT-T3 멱등 재전송 판정에 쓰는, 같은 채팅방·같은 사용자·같은 클라이언트 메시지 ID의 기존 USER 메시지 조회다. */
	Optional<MatchChatMessage> findByMatchChatRoomIdAndSenderUserIdAndClientMessageId(
		Long matchChatRoomId, Long senderUserId, String clientMessageId);

	/** CHAT-T4 최신 구간 이력 조회다. */
	List<MatchChatMessage> findByMatchChatRoomIdOrderByIdDesc(Long matchChatRoomId, Pageable pageable);

	/** CHAT-T4 {@code beforeMessageId} 커서 기준 과거 구간 이력 조회다. */
	List<MatchChatMessage> findByMatchChatRoomIdAndIdLessThanOrderByIdDesc(
		Long matchChatRoomId, Long id, Pageable pageable);
}
