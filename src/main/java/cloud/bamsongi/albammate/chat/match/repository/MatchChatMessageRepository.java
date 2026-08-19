package cloud.bamsongi.albammate.chat.match.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.bamsongi.albammate.chat.match.MatchChatSystemEventKey;
import cloud.bamsongi.albammate.chat.match.entity.MatchChatMessage;

public interface MatchChatMessageRepository extends JpaRepository<MatchChatMessage, Long> {

	Optional<MatchChatMessage> findByMatchChatRoomIdAndSystemEventKey(
		Long matchChatRoomId, MatchChatSystemEventKey systemEventKey);
}
