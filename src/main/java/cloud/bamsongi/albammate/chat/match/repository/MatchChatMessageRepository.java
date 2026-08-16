package cloud.bamsongi.albammate.chat.match.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.bamsongi.albammate.chat.match.entity.MatchChatMessage;

public interface MatchChatMessageRepository extends JpaRepository<MatchChatMessage, Long> {}
