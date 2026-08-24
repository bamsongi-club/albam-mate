package cloud.bamsongi.albammate.chat.dto;

/** 읽음 처리 HTTP 입력이다. 값 검증은 접근 확인 뒤 서비스가 수행한다. */
public record ChatRoomReadRequest(Long upToMessageId) {
}
