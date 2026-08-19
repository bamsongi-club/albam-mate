package cloud.bamsongi.albammate.chat.match.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.chat.match.MatchChatMessagePageResponse;
import cloud.bamsongi.albammate.chat.match.MatchChatMessageResponse;
import cloud.bamsongi.albammate.chat.match.MatchChatMessageType;
import cloud.bamsongi.albammate.chat.match.MatchChatSender;
import cloud.bamsongi.albammate.chat.match.entity.MatchChatMessage;
import cloud.bamsongi.albammate.chat.match.entity.MatchChatRoom;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatMessageRepository;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatRoomRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.matching.contract.MatchPartyAccessQuery;
import cloud.bamsongi.albammate.matching.contract.MatchPartyChatAccess;
import cloud.bamsongi.albammate.matching.contract.MatchPartyParticipantRefQuery;
import cloud.bamsongi.albammate.user.contract.UserQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * CHAT-T4 — {@link MatchPartyAccessQuery} 접근 판정 안에서 현재 Party의 커밋된 USER·SYSTEM 메시지 이력을 읽기 전용으로
 * 조회한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchChatMessageHistoryQueryService {

	private final MatchPartyAccessQuery matchPartyAccessQuery;
	private final MatchChatRoomRepository matchChatRoomRepository;
	private final MatchChatMessageRepository matchChatMessageRepository;
	private final MatchPartyParticipantRefQuery matchPartyParticipantRefQuery;
	private final UserQuery userQuery;

	@Transactional(readOnly = true)
	public MatchChatMessagePageResponse history(long currentUserId, long partyId, Long beforeMessageId, int size) {
		requireAllowed(matchPartyAccessQuery.evaluateChatAccess(currentUserId, partyId));
		return queryHistory(currentUserId, partyId, beforeMessageId, size);
	}

	private void requireAllowed(MatchPartyChatAccess chatAccess) {
		if (chatAccess == MatchPartyChatAccess.NOT_ACTIVE) {
			throw new BusinessException(ErrorCode.MATCH_CHAT_NOT_ACTIVE);
		}
		if (chatAccess == MatchPartyChatAccess.FORBIDDEN) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
	}

	private MatchChatMessagePageResponse queryHistory(
		long currentUserId, long partyId, Long beforeMessageId, int size) {
		MatchChatRoom chatRoom = matchChatRoomRepository.findByPartyId(partyId)
			.orElseThrow(() -> missingChatRoom(partyId));
		Pageable pageable = PageRequest.of(0, size + 1);
		List<MatchChatMessage> fetched = beforeMessageId == null
			? matchChatMessageRepository.findByMatchChatRoomIdOrderByIdDesc(chatRoom.getId(), pageable)
			: matchChatMessageRepository
				.findByMatchChatRoomIdAndIdLessThanOrderByIdDesc(chatRoom.getId(), beforeMessageId, pageable);

		boolean hasNext = fetched.size() > size;
		List<MatchChatMessage> page = hasNext ? fetched.subList(0, size) : fetched;
		Set<Long> senderUserIds = page.stream()
			.map(MatchChatMessage::getSenderUserId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
		Map<Long, String> participantRefs = senderUserIds.isEmpty()
			? Map.of()
			: matchPartyParticipantRefQuery.findParticipantRefs(partyId, senderUserIds);
		Map<Long, String> nicknames = senderUserIds.isEmpty()
			? Map.of()
			: userQuery.findNicknamesByIds(senderUserIds);

		List<MatchChatMessageResponse> messages = page.stream()
			.map(message -> toResponse(message, partyId, currentUserId, participantRefs, nicknames))
			.toList();
		Long nextBeforeMessageId = hasNext ? page.get(page.size() - 1).getId() : null;
		return new MatchChatMessagePageResponse(messages, nextBeforeMessageId, hasNext);
	}

	private MatchChatMessageResponse toResponse(
		MatchChatMessage message,
		long partyId,
		long currentUserId,
		Map<Long, String> participantRefs,
		Map<Long, String> nicknames) {
		if (message.getMessageType() == MatchChatMessageType.SYSTEM) {
			return MatchChatMessageResponse.from(message, partyId, null, false);
		}
		long senderUserId = message.getSenderUserId();
		MatchChatSender sender = new MatchChatSender(
			senderRefOrMissing(partyId, senderUserId, participantRefs),
			senderNicknameOrMissing(partyId, senderUserId, nicknames));
		return MatchChatMessageResponse.from(message, partyId, sender, senderUserId == currentUserId);
	}

	private String senderRefOrMissing(long partyId, long senderUserId, Map<Long, String> participantRefs) {
		String participantRef = participantRefs.get(senderUserId);
		if (participantRef == null) {
			log.atError().addKeyValue("event", "match_chat_message_sender_participant_ref_missing")
				.addKeyValue("partyId", partyId).log("match chat message sender participant ref missing");
			throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
		}
		return participantRef;
	}

	private String senderNicknameOrMissing(long partyId, long senderUserId, Map<Long, String> nicknames) {
		String nickname = nicknames.get(senderUserId);
		if (nickname == null) {
			log.atError().addKeyValue("event", "match_chat_message_sender_nickname_missing")
				.addKeyValue("partyId", partyId).log("match chat message sender nickname missing");
			throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
		}
		return nickname;
	}

	private BusinessException missingChatRoom(long partyId) {
		log.atError().addKeyValue("event", "match_chat_room_not_provisioned")
			.addKeyValue("partyId", partyId).log("match chat room not provisioned for active party");
		return new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
	}
}
