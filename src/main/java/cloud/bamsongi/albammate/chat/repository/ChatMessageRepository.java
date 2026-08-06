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

	/** CHAT-03 실시간 catch-up이 연결별 마지막 전달 ID 이후의 누락 메시지를 오름차순으로 복구할 때 사용한다. */
	List<ChatMessage> findByChatRoomIdAndIdGreaterThanOrderByIdAsc(Long chatRoomId, Long afterMessageId);

	/** 재연결 cursor가 현재 채팅방 이력에 속하는지 확인해 다른 방 메시지 ID로 인한 누락을 막는다. */
	boolean existsByIdAndChatRoomId(Long id, Long chatRoomId);
}
