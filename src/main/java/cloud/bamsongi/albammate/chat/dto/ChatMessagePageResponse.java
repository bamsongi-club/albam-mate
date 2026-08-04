package cloud.bamsongi.albammate.chat.dto;

import java.util.List;

/** 채팅 이력 조회 응답이며, messageId 내림차순 구간과 다음 과거 구간 경계를 담는다. */
public record ChatMessagePageResponse(
	List<ChatMessageResponse> messages,
	Long nextBeforeMessageId,
	boolean hasNext) {
}
