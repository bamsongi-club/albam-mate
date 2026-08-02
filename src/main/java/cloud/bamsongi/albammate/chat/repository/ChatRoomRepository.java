package cloud.bamsongi.albammate.chat.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.bamsongi.albammate.chat.entity.ChatRoom;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {}
