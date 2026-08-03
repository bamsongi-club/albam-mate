package cloud.bamsongi.albammate.chat.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;

@SpringBootTest
@Transactional
class ChatMessagePersistenceModelTest {

	private static final Instant CREATED_AT = Instant.parse("2026-08-03T00:00:00Z");

	@Autowired
	private ChatMessageRepository chatMessageRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void 유효한_메시지_저장은_방과_작성자_식별자를_보존한다() {
		MessageStorage storage = createMessageStorage("sender-t1@example.com");

		ChatMessage message = chatMessageRepository.saveAndFlush(
			ChatMessage.create(storage.chatRoomId(), storage.senderUserId(), "client-message-1", "안녕하세요", CREATED_AT));

		List<ChatMessage> messages = chatMessageRepository.findByChatRoomIdOrderByIdDesc(
			storage.chatRoomId(), PageRequest.of(0, 10));
		assertNotNull(message.getId());
		assertEquals(1, messages.size());
		assertEquals(storage.chatRoomId(), messages.getFirst().getChatRoomId());
		assertEquals(storage.senderUserId(), messages.getFirst().getSenderUserId());
		assertEquals("client-message-1", messages.getFirst().getClientMessageId());
		assertEquals("안녕하세요", messages.getFirst().getContent());
		assertEquals(CREATED_AT, messages.getFirst().getCreatedAt());
	}

	@Test
	void 같은_방_작성자_clientMessageId는_H2_유일_제약으로_하나만_허용된다() {
		MessageStorage storage = createMessageStorage("sender-t2@example.com");
		chatMessageRepository.saveAndFlush(
			ChatMessage.create(storage.chatRoomId(), storage.senderUserId(), "client-message-1", "첫 메시지", CREATED_AT));

		assertThrows(
			DataIntegrityViolationException.class,
			() -> chatMessageRepository.saveAndFlush(
				ChatMessage.create(
					storage.chatRoomId(), storage.senderUserId(), "client-message-1", "중복 메시지", CREATED_AT)));
	}

	private MessageStorage createMessageStorage(String email) {
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', '작성자', ?, ?)",
			email,
			CREATED_AT,
			CREATED_AT);
		Long senderUserId = jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
		jdbcTemplate.update(
			"insert into rooms (host_user_id, room_type, title, experience_level, is_rulemaster_led, capacity, "
				+ "active_participant_count, start_at, place, status, created_at, updated_at) values "
				+ "(?, 'PERSON_FOCUSED', '메시지 저장 검증 방', 'ALL_LEVELS', false, 2, 0, ?, '홍대', "
				+ "'RECRUITING', ?, ?)",
			senderUserId,
			CREATED_AT.plusSeconds(3600),
			CREATED_AT,
			CREATED_AT);
		Long roomId = jdbcTemplate.queryForObject("select id from rooms where host_user_id = ?", Long.class,
			senderUserId);
		jdbcTemplate.update(
			"insert into chat_rooms (room_id, created_at, updated_at) values (?, ?, ?)",
			roomId,
			CREATED_AT,
			CREATED_AT);
		Long chatRoomId = jdbcTemplate.queryForObject(
			"select id from chat_rooms where room_id = ?", Long.class, roomId);
		return new MessageStorage(chatRoomId, senderUserId);
	}

	private record MessageStorage(Long chatRoomId, Long senderUserId) {
	}
}
