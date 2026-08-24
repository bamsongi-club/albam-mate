package cloud.bamsongi.albammate.chat.dto;

/** 채팅 메시지에 노출하는 작성자 표시 정보다. */
public record ChatMessageSender(String nickname, String profileImageUrl) {
}
