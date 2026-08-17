package cloud.bamsongi.albammate.chat.match.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.bamsongi.albammate.chat.match.entity.MatchChatRoom;

public interface MatchChatRoomRepository extends JpaRepository<MatchChatRoom, Long> {

	Optional<MatchChatRoom> findByPartyId(Long partyId);
}
