package cloud.bamsongi.albammate.chat.match.adapter;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.chat.match.entity.MatchChatRoom;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatRoomRepository;
import cloud.bamsongi.albammate.matching.contract.MatchChatProvisionPort;
import lombok.RequiredArgsConstructor;

/**
 * {@code partyId}별 MATCH 채팅방을 멱등 준비하는 {@code matching.contract} 공개 port 구현이다.
 *
 * <p>호출자(matching의 Recovery Executor) 트랜잭션에 {@link Propagation#MANDATORY}로 참여하므로, 호출자 트랜잭션이
 * rollback되면 이 메서드가 만든 변경도 함께 사라진다.
 */
@Component
@RequiredArgsConstructor
public class MatchChatProvisionAdapter implements MatchChatProvisionPort {

	private final MatchChatRoomRepository matchChatRoomRepository;

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void provision(long partyId) {
		matchChatRoomRepository.findByPartyId(partyId)
			.orElseGet(() -> matchChatRoomRepository.save(MatchChatRoom.of(partyId)));
	}
}
