package cloud.bamsongi.albammate.chat.match.adapter;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.chat.match.repository.MatchChatRoomRepository;
import cloud.bamsongi.albammate.matching.contract.MatchChatCleanupPort;
import lombok.RequiredArgsConstructor;

/**
 * {@code partyId}별 MATCH 채팅방을 삭제해 FK cascade로 메시지까지 정리하는 {@code matching.contract} 공개 port 구현이다.
 *
 * <p>호출자(matching의 Recovery/Cleanup Executor) 트랜잭션에 {@link Propagation#MANDATORY}로 참여하므로, 호출자 트랜잭션이
 * rollback되면 방과 메시지 삭제도 함께 원복된다.
 */
@Component
@RequiredArgsConstructor
public class MatchChatCleanupAdapter implements MatchChatCleanupPort {

	private final MatchChatRoomRepository matchChatRoomRepository;

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void cleanup(long partyId) {
		matchChatRoomRepository.findByPartyId(partyId).ifPresent(matchChatRoomRepository::delete);
	}
}
