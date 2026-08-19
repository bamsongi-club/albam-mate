package cloud.bamsongi.albammate.chat.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.chat.dto.ChatMessageResponse;
import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatMessageType;
import cloud.bamsongi.albammate.chat.entity.ChatSystemEventKey;
import cloud.bamsongi.albammate.user.contract.UserQuery;

/** #870 T1 — 이력·실시간이 공유하는 SYSTEM 응답 조립기의 문장 생성과 프로필 미조회 fallback을 단위로 검증한다. */
class ChatMessageResponseAssemblerTest {

	private final ChatMessageResponseAssembler assembler = new ChatMessageResponseAssembler();

	@Test
	void 대상_프로필을_찾지_못하면_고정_대체_표시명으로_문장을_조립하고_조회를_실패시키지_않는다() {
		ChatMessage message = mock(ChatMessage.class);
		when(message.getId()).thenReturn(1L);
		when(message.getSystemEventKey()).thenReturn(ChatSystemEventKey.PARTICIPANT_ENTERED);
		when(message.getCreatedAt()).thenReturn(Instant.parse("2026-08-04T00:00:00Z"));

		ChatMessageResponse response = assembler.assembleSystemMessage(message, 1L, null);

		assertEquals(ChatMessageType.SYSTEM, response.messageType());
		assertEquals("알 수 없는 사용자님이 입장했어요.", response.content());
		assertEquals("알 수 없는 사용자", response.subject().nickname());
		assertNull(response.subject().profileImageUrl());
		assertNull(response.sender());
		assertFalse(response.isMine());
	}

	@Test
	void PARTICIPANT_LEFT는_퇴장_문구로_조립된다() {
		ChatMessage message = mock(ChatMessage.class);
		when(message.getId()).thenReturn(2L);
		when(message.getSystemEventKey()).thenReturn(ChatSystemEventKey.PARTICIPANT_LEFT);
		when(message.getCreatedAt()).thenReturn(Instant.parse("2026-08-04T00:00:00Z"));
		UserQuery.UserSummary subject = new UserQuery.UserSummary("참가자", null);

		ChatMessageResponse response = assembler.assembleSystemMessage(message, 1L, subject);

		assertEquals("참가자님이 나갔어요.", response.content());
	}
}
