package cloud.bamsongi.albammate.chat.match;

/** {@code USER} 메시지 작성자의 Party-scoped opaque participant reference와 현재 공개 닉네임이다. */
public record MatchChatSender(String participantRef, String nickname) {
}
