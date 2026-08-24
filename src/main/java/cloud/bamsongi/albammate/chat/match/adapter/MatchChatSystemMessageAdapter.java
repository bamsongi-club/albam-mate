package cloud.bamsongi.albammate.chat.match.adapter;

import java.time.Clock;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.chat.match.MatchChatSystemEventKey;
import cloud.bamsongi.albammate.chat.match.entity.MatchChatMessage;
import cloud.bamsongi.albammate.chat.match.entity.MatchChatRoom;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatMessageRepository;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatRoomRepository;
import cloud.bamsongi.albammate.matching.contract.MatchChatSystemMessagePort;
import lombok.RequiredArgsConstructor;

/**
 * Party lifecycle SYSTEM 알림을 {@code partyId}·이벤트 키별로 멱등 저장하는 {@code matching.contract} 공개 port
 * 구현이다.
 *
 * <p>호출자(matching이 Party 상태를 잠근 Executor) 트랜잭션에 {@link Propagation#MANDATORY}로 참여하므로, 호출자
 * 트랜잭션이 rollback되면 이 메서드가 만든 변경도 함께 사라진다. 채팅방은 {@link MatchChatProvisionAdapter}가 이미 준비했다고
 * 전제하며, 없으면 provisioning 순서를 어긴 호출로 보고 예외를 던진다.
 */
@Component
@RequiredArgsConstructor
public class MatchChatSystemMessageAdapter implements MatchChatSystemMessagePort {

	private final MatchChatRoomRepository matchChatRoomRepository;
	private final MatchChatMessageRepository matchChatMessageRepository;
	private final Clock clock;

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(long partyId, String eventKey) {
		MatchChatSystemEventKey systemEventKey = MatchChatSystemEventKey.valueOf(eventKey);
		MatchChatRoom room = matchChatRoomRepository.findByPartyId(partyId)
			.orElseThrow(
				() -> new IllegalStateException("MATCH chat room not provisioned for party " + partyId));
		matchChatMessageRepository.findByMatchChatRoomIdAndSystemEventKey(room.getId(), systemEventKey)
			.orElseGet(
				() -> matchChatMessageRepository.save(
					MatchChatMessage.createSystemMessage(
						room.getId(), systemEventKey, contentFor(systemEventKey), clock.instant())));
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
	public boolean hasPersistedEvent(long partyId, String eventKey) {
		MatchChatSystemEventKey systemEventKey = MatchChatSystemEventKey.valueOf(eventKey);
		MatchChatRoom room = matchChatRoomRepository.findByPartyId(partyId).orElse(null);
		if (room == null) {
			return false;
		}
		return matchChatMessageRepository
			.findByMatchChatRoomIdAndSystemEventKey(room.getId(), systemEventKey)
			.isPresent();
	}

	private String contentFor(MatchChatSystemEventKey systemEventKey) {
		return switch (systemEventKey) {
			case CHAT_OPENED -> "채팅이 열렸습니다.";
			case CLOSES_IN_ONE_HOUR -> "채팅이 1시간 이내에 종료됩니다.";
		};
	}
}
