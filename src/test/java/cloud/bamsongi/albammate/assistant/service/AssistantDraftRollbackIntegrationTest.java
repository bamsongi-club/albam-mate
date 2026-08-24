package cloud.bamsongi.albammate.assistant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.willThrow;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import cloud.bamsongi.albammate.assistant.dto.AssistantConsentDecision;
import cloud.bamsongi.albammate.assistant.dto.AssistantConsentRequest;
import cloud.bamsongi.albammate.assistant.dto.AssistantDraftCreateRequest;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;

/** T6: Room handoff의 어느 실패도 assistant 측 중간 저장을 남기지 않는다. */
@SpringBootTest(properties = {
	"app.assistant.enabled=true", "app.assistant.no-retention-verified=true",
	"app.assistant.no-training-verified=true", "app.assistant.policy-version=OPENAI-POLICY-2026-08",
	"app.assistant.policy-url=https://openai.com/policies/api-data-usage-policies"
})
class AssistantDraftRollbackIntegrationTest {

	@Autowired
	private AssistantDraftService draftService;
	@Autowired
	private AssistantConsentService consentService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@MockitoBean
	private ChatRoomRepository chatRoomRepository;

	@Test
	void T6_ChatRoom_저장_실패는_초안_멱등기록_Room과_ChatRoom을_함께_롤백한다() {
		long userId = userRepository.saveAndFlush(User.create("rollback-ai03@example.com", "{bcrypt}hash", "롤백 사용자"))
			.getId();
		consentService.changeConsent(userId,
			new AssistantConsentRequest(AssistantConsentDecision.GRANT, "AI-01-CONSENT-V1"));
		long draftId = draftService.create(userId, new AssistantDraftCreateRequest(
			"PERSON_FOCUSED", "롤백 초안", null, null, "ALL_LEVELS", false,
			Instant.parse("2030-01-01T12:00:00Z"), "홍대", "카페", 3)).draftId();
		willThrow(new IllegalStateException("forced chat room persistence failure"))
			.given(chatRoomRepository).save(org.mockito.ArgumentMatchers.any(ChatRoom.class));
		org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
			org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
				new CurrentUserPrincipal(userId), null,
				org.springframework.security.core.authority.AuthorityUtils.NO_AUTHORITIES));

		try {
			assertThrows(IllegalStateException.class,
				() -> draftService.confirm(userId, draftId, 0, "rollback-handoff-key"));
		} finally {
			org.springframework.security.core.context.SecurityContextHolder.clearContext();
		}

		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from assistant_idempotency_records where draft_id = ?", Integer.class, draftId));
		assertEquals("ACTIVE",
			jdbcTemplate.queryForObject("select status from assistant_drafts where id = ?", String.class, draftId));
		assertEquals(0,
			jdbcTemplate.queryForObject("select count(*) from rooms where host_user_id = ?", Integer.class, userId));
		assertEquals(0, jdbcTemplate.queryForObject("""
			select count(*) from chat_rooms chat_room
			join rooms room on room.id = chat_room.room_id
			where room.host_user_id = ?
			""", Integer.class, userId));
	}
}
