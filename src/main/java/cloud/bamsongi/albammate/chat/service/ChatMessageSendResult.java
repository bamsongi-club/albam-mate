package cloud.bamsongi.albammate.chat.service;

import cloud.bamsongi.albammate.chat.dto.ChatMessageResponse;

/** 메시지 전송 결과와 이번 요청이 신규 저장인지 여부를 함께 반환한다. */
public record ChatMessageSendResult(ChatMessageResponse message, boolean created) {
}
