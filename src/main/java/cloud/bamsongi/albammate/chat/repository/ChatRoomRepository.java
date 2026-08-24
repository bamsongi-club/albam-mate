package cloud.bamsongi.albammate.chat.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import jakarta.persistence.LockModeType;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

	Optional<ChatRoom> findByRoomId(Long roomId);

	/** 방별 메시지 추가 순서와 멱등성 판정을 직렬화하기 위한 쓰기 잠금 조회다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select chatRoom from ChatRoom chatRoom where chatRoom.roomId = :roomId")
	Optional<ChatRoom> findByRoomIdForMessageAppend(
		@Param("roomId")
		Long roomId);
}
