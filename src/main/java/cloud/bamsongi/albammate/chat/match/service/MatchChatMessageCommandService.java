package cloud.bamsongi.albammate.chat.match.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.chat.match.MatchChatMessageCommitted;
import cloud.bamsongi.albammate.chat.match.MatchChatMessageResponse;
import cloud.bamsongi.albammate.chat.match.MatchChatMessageSendRequest;
import cloud.bamsongi.albammate.chat.match.MatchChatSender;
import cloud.bamsongi.albammate.chat.match.entity.MatchChatMessage;
import cloud.bamsongi.albammate.chat.match.entity.MatchChatRoom;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatMessageRepository;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatRoomRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.matching.contract.MatchPartyChatWriteGuard;
import cloud.bamsongi.albammate.matching.contract.MatchPartyParticipantRefQuery;
import cloud.bamsongi.albammate.user.contract.UserQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * CHAT-T3 — {@link MatchPartyChatWriteGuard}가 잠근 Party 안에서 MATCH 채팅 메시지를 검증·정규화하고 멱등 저장한다.
 *
 * <p>접근 판정과 Party 잠금은 {@code matching.contract}가 소유하므로, 이 service는 접근이 허용된 뒤에만 실행되는 검증·저장
 * 로직만 책임진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchChatMessageCommandService {

	private static final int MAX_CLIENT_MESSAGE_ID_LENGTH = 100;
	private static final int MAX_CONTENT_LENGTH = 500;
	/** LF만 줄바꿈으로 허용하고, 나머지 제어문자는 공백 제거 전에 거절한다. */
	private static final Pattern DISALLOWED_CONTROL_CHARACTER_PATTERN = Pattern.compile("[\\p{Cc}&&[^\\n]]");

	private final MatchPartyChatWriteGuard matchPartyChatWriteGuard;
	private final MatchChatRoomRepository matchChatRoomRepository;
	private final MatchChatMessageRepository matchChatMessageRepository;
	private final MatchPartyParticipantRefQuery matchPartyParticipantRefQuery;
	private final UserQuery userQuery;
	private final ApplicationEventPublisher eventPublisher;
	private final Clock clock;

	@Transactional
	public MatchChatMessageSendResult send(long currentUserId, long partyId, MatchChatMessageSendRequest request) {
		Objects.requireNonNull(request, "request");
		return matchPartyChatWriteGuard.executeWithActiveAccess(
			currentUserId, partyId, () -> appendMessage(currentUserId, partyId, request));
	}

	private MatchChatMessageSendResult appendMessage(
		long currentUserId, long partyId, MatchChatMessageSendRequest request) {
		MatchChatRoom chatRoom = matchChatRoomRepository.findByPartyId(partyId)
			.orElseThrow(() -> missingChatRoom(partyId));
		String clientMessageId = validateClientMessageId(request.clientMessageId());
		String content = normalizeContent(request.content());
		MatchChatSender sender = requireSender(partyId, currentUserId);

		return matchChatMessageRepository
			.findByMatchChatRoomIdAndSenderUserIdAndClientMessageId(chatRoom.getId(), currentUserId, clientMessageId)
			.map(existing -> existingMessage(existing, partyId, content, sender))
			.orElseGet(() -> saveNewMessage(chatRoom, currentUserId, partyId, clientMessageId, content, sender));
	}

	private MatchChatMessageSendResult existingMessage(
		MatchChatMessage existing, long partyId, String content, MatchChatSender sender) {
		if (!existing.getContent().equals(content)) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		return new MatchChatMessageSendResult(
			MatchChatMessageResponse.from(existing, partyId, sender, true), false);
	}

	private MatchChatMessageSendResult saveNewMessage(
		MatchChatRoom chatRoom,
		long currentUserId,
		long partyId,
		String clientMessageId,
		String content,
		MatchChatSender sender) {
		MatchChatMessage saved = matchChatMessageRepository.save(
			MatchChatMessage.createUserMessage(chatRoom.getId(), currentUserId, clientMessageId, content,
				Instant.now(clock)));
		eventPublisher.publishEvent(MatchChatMessageCommitted.messageCreated(partyId, saved.getId()));
		return new MatchChatMessageSendResult(MatchChatMessageResponse.from(saved, partyId, sender, true), true);
	}

	private String validateClientMessageId(String clientMessageId) {
		if (clientMessageId == null
			|| clientMessageId.isEmpty()
			|| clientMessageId.length() > MAX_CLIENT_MESSAGE_ID_LENGTH) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		return clientMessageId;
	}

	private String normalizeContent(String content) {
		if (content == null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		String normalized = content.replace("\r\n", "\n");
		if (DISALLOWED_CONTROL_CHARACTER_PATTERN.matcher(normalized).find()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		normalized = normalized.strip();
		if (normalized.isEmpty() || normalized.length() > MAX_CONTENT_LENGTH) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		return normalized;
	}

	/** 발신자 participantRef·닉네임을 조회하며, 접근 검증을 통과했는데도 찾지 못하는 예기치 않은 상태만 partyId로 기록한다. */
	private MatchChatSender requireSender(long partyId, long currentUserId) {
		String participantRef = matchPartyParticipantRefQuery.findParticipantRef(partyId, currentUserId)
			.orElseThrow(() -> {
				log.atError().addKeyValue("event", "match_chat_message_sender_participant_ref_missing")
					.addKeyValue("partyId", partyId).log("match chat message sender participant ref missing");
				return new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
			});
		String nickname = userQuery.findNicknameById(currentUserId)
			.orElseThrow(() -> {
				log.atError().addKeyValue("event", "match_chat_message_sender_nickname_missing")
					.addKeyValue("partyId", partyId).log("match chat message sender nickname missing");
				return new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
			});
		return new MatchChatSender(participantRef, nickname);
	}

	private BusinessException missingChatRoom(long partyId) {
		log.atError().addKeyValue("event", "match_chat_room_not_provisioned")
			.addKeyValue("partyId", partyId).log("match chat room not provisioned for active party");
		return new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
	}
}
