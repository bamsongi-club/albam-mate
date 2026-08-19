package cloud.bamsongi.albammate.chat.match;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import cloud.bamsongi.albammate.chat.match.entity.MatchChatMessage;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatMessageRepository;
import cloud.bamsongi.albammate.matching.contract.MatchPartyParticipantRefQuery;
import cloud.bamsongi.albammate.user.contract.UserQuery;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * 연결별 마지막 전달 ID 이후의 PostgreSQL 메시지를 {@code messageId} 오름차순으로 다시 조회해 전달하는 catch-up
 * 책임을 진다.
 *
 * <p>호출자({@link MatchChatWebSocketHandler})가 연결의 {@link MatchChatPartyConnection#lock}을 잡은 채 호출해,
 * 같은 연결에 대한 전달을 직렬화한다.
 */
@Component
@RequiredArgsConstructor
class MatchChatMessageDeliveryService {

	private static final Logger log = LoggerFactory.getLogger(MatchChatMessageDeliveryService.class);

	@NonNull private final MatchChatConnectionRegistry connectionRegistry;
	@NonNull private final MatchChatMessageRepository matchChatMessageRepository;
	@NonNull private final MatchPartyParticipantRefQuery matchPartyParticipantRefQuery;
	@NonNull private final UserQuery userQuery;
	@NonNull private final MatchChatWebSocketMetrics metrics;
	@NonNull private final ObjectMapper objectMapper;
	@NonNull private final Clock clock;

	/**
	 * 연결의 마지막 전달 ID 이후 메시지만 오름차순으로 보내고 전달한 만큼 기준을 갱신한다.
	 *
	 * <p>전송이 실패하면 그 메시지에서 멈추고 연결을 {@code SERVER_ERROR}로 종료하며 실패를 계측한다.
	 */
	void deliverNewMessages(MatchChatPartyConnection connection) {
		List<MatchChatMessage> newMessages = matchChatMessageRepository
			.findByMatchChatRoomIdAndIdGreaterThanOrderByIdAsc(
				connection.matchChatRoomId, connection.lastDeliveredMessageId.get());
		if (newMessages.isEmpty()) {
			return;
		}
		Set<Long> senderUserIds = newMessages.stream()
			.map(MatchChatMessage::getSenderUserId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
		Map<Long, String> participantRefs = senderUserIds.isEmpty()
			? Map.of()
			: matchPartyParticipantRefQuery.findParticipantRefs(connection.partyId, senderUserIds);
		Map<Long, String> nicknames = senderUserIds.isEmpty()
			? Map.of()
			: userQuery.findNicknamesByIds(senderUserIds);

		int delivered = 0;
		for (MatchChatMessage message : newMessages) {
			if (connectionRegistry.shouldStopDelivery(connection.session)) {
				break;
			}
			MatchChatSender sender = sender(message, connection.partyId, participantRefs, nicknames);
			if (sender == MISSING_SENDER) {
				metrics.recordDeliveryFailure();
				connectionRegistry.closeForTransportFailure(connection.session);
				break;
			}
			MatchChatMessageResponse response = MatchChatMessageResponse.from(
				message, connection.partyId, sender, isMine(message, connection.userId));
			if (!send(connection.session, MatchChatMessageEvent.messageCreated(response))) {
				metrics.recordDeliveryFailure();
				connectionRegistry.closeForTransportFailure(connection.session);
				break;
			}
			connection.lastDeliveredMessageId.set(message.getId());
			metrics.recordDeliveryLatency(Duration.between(message.getCreatedAt(), clock.instant()));
			delivered++;
		}
		metrics.recordRecoveredMessages(delivered);
	}

	private static final MatchChatSender MISSING_SENDER = new MatchChatSender(null, null);

	/** SYSTEM 메시지는 sender=null로 전달하고, USER 메시지는 발신자 정보를 찾지 못하면 전송을 중단하는 표식을 돌려준다. */
	private MatchChatSender sender(
		MatchChatMessage message, long partyId, Map<Long, String> participantRefs, Map<Long, String> nicknames) {
		if (message.getMessageType() == MatchChatMessageType.SYSTEM) {
			return null;
		}
		long senderUserId = message.getSenderUserId();
		String participantRef = participantRefs.get(senderUserId);
		String nickname = nicknames.get(senderUserId);
		if (participantRef == null || nickname == null) {
			log.atError().addKeyValue("event", "match_chat_message_sender_missing")
				.addKeyValue("partyId", partyId).log("match chat message sender participant ref or nickname missing");
			return MISSING_SENDER;
		}
		return new MatchChatSender(participantRef, nickname);
	}

	private boolean isMine(MatchChatMessage message, long userId) {
		return message.getSenderUserId() != null && message.getSenderUserId() == userId;
	}

	private boolean send(WebSocketSession session, MatchChatMessageEvent event) {
		try {
			session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
			return true;
		} catch (IOException | RuntimeException exception) {
			return false;
		}
	}
}
