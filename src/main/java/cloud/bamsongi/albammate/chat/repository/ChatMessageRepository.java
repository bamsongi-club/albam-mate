package cloud.bamsongi.albammate.chat.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import cloud.bamsongi.albammate.chat.entity.ChatMessage;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

	List<ChatMessage> findByChatRoomIdOrderByIdDesc(Long chatRoomId, Pageable pageable);

	List<ChatMessage> findByChatRoomIdAndIdLessThanOrderByIdDesc(Long chatRoomId, Long beforeMessageId,
		Pageable pageable);

	Optional<ChatMessage> findByChatRoomIdAndSenderUserIdAndClientMessageId(
		Long chatRoomId, Long senderUserId, String clientMessageId);
}
