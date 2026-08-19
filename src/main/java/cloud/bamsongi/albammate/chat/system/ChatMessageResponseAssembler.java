package cloud.bamsongi.albammate.chat.system;

import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.chat.dto.ChatMessageResponse;
import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatSystemEventKey;
import cloud.bamsongi.albammate.user.contract.UserQuery;

/**
 * 이력 조회와 실시간·재연결 전달이 공유하는 SYSTEM 응답 조립기다.
 *
 * <p>안내 문장은 저장하지 않고 이 클래스가 조회 시점에 조립한다. 대상 사용자의 공개 프로필을 찾지 못해도 조회를
 * 실패시키지 않고 고정 대체 표시명으로 수렴한다.
 */
@Component
public class ChatMessageResponseAssembler {

	static final String UNKNOWN_DISPLAY_NAME = "알 수 없는 사용자";
	private static final UserQuery.UserSummary UNKNOWN_SUMMARY = new UserQuery.UserSummary(
		UNKNOWN_DISPLAY_NAME, null);

	/** USER 메시지 응답을 조립한다. 발신자 요약은 호출자가 이미 해결한 값이어야 한다. */
	public ChatMessageResponse assembleUserMessage(
		ChatMessage message, long roomId, UserQuery.UserSummary sender, boolean isMine) {
		return ChatMessageResponse.forUser(message, roomId, sender.nickname(), sender.profileImageUrl(), isMine);
	}

	/** SYSTEM 메시지 응답을 조립한다. 대상 프로필이 없으면 고정 대체 표시명을 쓴다. */
	public ChatMessageResponse assembleSystemMessage(
		ChatMessage message, long roomId, UserQuery.UserSummary subjectOrNull) {
		UserQuery.UserSummary subject = subjectOrNull == null ? UNKNOWN_SUMMARY : subjectOrNull;
		String content = compose(message.getSystemEventKey(), subject.nickname());
		return ChatMessageResponse.forSystem(
			message, roomId, message.getSystemEventKey(), subject.nickname(), subject.profileImageUrl(), content);
	}

	private String compose(ChatSystemEventKey eventKey, String nickname) {
		return switch (eventKey) {
			case PARTICIPANT_ENTERED -> nickname + "님이 입장했어요.";
			case PARTICIPANT_LEFT -> nickname + "님이 나갔어요.";
		};
	}
}
