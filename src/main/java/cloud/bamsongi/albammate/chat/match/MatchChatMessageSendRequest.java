package cloud.bamsongi.albammate.chat.match;

/** MATCH 채팅 메시지 전송 HTTP 입력이다. 의미 검증과 본문 정규화는 접근 확인 뒤 서비스가 수행한다. */
public record MatchChatMessageSendRequest(String clientMessageId, String content) {
}
