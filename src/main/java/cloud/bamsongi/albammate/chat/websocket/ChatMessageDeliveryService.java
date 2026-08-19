package cloud.bamsongi.albammate.chat.websocket;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import cloud.bamsongi.albammate.chat.dto.ChatMessageEvent;
import cloud.bamsongi.albammate.chat.dto.ChatMessageResponse;
import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.user.contract.UserQuery;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * 연결별 마지막 전달 ID 이후의 PostgreSQL 메시지를 {@code messageId} 오름차순으로 다시 조회해 전달하는 catch-up
 * 책임을 진다.
 *
 * <p>호출자({@link ChatWebSocketHandler})가 연결의 {@link ChatRoomConnection#lock}을 잡은 채 호출해, 같은
 * 연결에 대한 전달과 스케줄 접근 재검증을 직렬화한다.
 */
@Component
@RequiredArgsConstructor
class ChatMessageDeliveryService {

	private static final Logger log = LoggerFactory.getLogger(ChatMessageDeliveryService.class);

	@NonNull private final ChatConnectionRegistry connectionRegistry;
	@NonNull private final ChatMessageRepository chatMessageRepository;
	@NonNull private final UserQuery userQuery;
	@NonNull private final ChatWebSocketMetrics metrics;
	@NonNull private final ObjectMapper objectMapper;
	@NonNull private final Clock clock;

	/**
	 * 연결의 마지막 전달 ID 이후 메시지만 오름차순으로 보내고 전달한 만큼 기준을 갱신한다.
	 *
	 * <p>전송이 실패하면 그 메시지에서 멈추고 연결을 {@code SERVER_ERROR}로 종료하며 실패를 계측한다.
	 */
	void deliverNewMessages(ChatRoomConnection connection) {
		List<ChatMessage> newMessages = chatMessageRepository
			.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(connection.chatRoomId,
				connection.lastDeliveredMessageId.get());
		if (newMessages.isEmpty()) {
			return;
		}
		Map<Long, UserQuery.UserSummary> senderSummaries = userQuery.findUserSummariesByIds(
			newMessages.stream().map(ChatMessage::getSenderUserId).collect(Collectors.toSet()));
		int delivered = 0;
		for (ChatMessage message : newMessages) {
			if (connectionRegistry.shouldStopDelivery(connection.session)) {
				break;
			}
			UserQuery.UserSummary sender = senderSummaries.get(message.getSenderUserId());
			if (sender == null) {
				log.atError().addKeyValue("event", "chat_message_sender_nickname_missing")
					.addKeyValue("roomId", connection.roomId).log("chat message sender nickname missing");
				metrics.recordDeliveryFailure();
				connectionRegistry.closeForTransportFailure(connection.session);
				break;
			}
			ChatMessageResponse response = ChatMessageResponse.from(
				message,
				connection.roomId,
				sender.nickname(),
				sender.profileImageUrl(),
				message.getSenderUserId().equals(connection.userId));
			if (!send(connection.session, ChatMessageEvent.messageCreated(response))) {
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

	private boolean send(WebSocketSession session, ChatMessageEvent event) {
		try {
			session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
			return true;
		} catch (IOException | RuntimeException exception) {
			return false;
		}
	}
}
