package cloud.bamsongi.albammate.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.bamsongi.albammate.chat.contract.ChatRealtimePublisher;
import cloud.bamsongi.albammate.chat.contract.MessageCommitted;
import cloud.bamsongi.albammate.chat.dto.ChatMessageSendRequest;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@SpringBootTest
@Import(ChatMessageCommandServiceIntegrationTest.TestBeans.class)
class ChatMessageCommandServiceIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

	@Autowired
	private ChatMessageCommandService chatMessageCommandService;
	@Autowired
	private ChatMessageRepository chatMessageRepository;
	@Autowired
	private ChatRoomRepository chatRoomRepository;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private ParticipationRepository participationRepository;
	@Autowired
	private RoomParticipationService roomParticipationService;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private TransactionTemplate transactionTemplate;
	@Autowired
	private MeterRegistry meterRegistry;
	@Autowired
	private RecordingChatRealtimePublisher realtimePublisher;

	private final List<Long> userIds = new ArrayList<>();
	private final List<Long> roomIds = new ArrayList<>();
	private final List<Long> chatRoomIds = new ArrayList<>();
	private final List<Long> participationIds = new ArrayList<>();

	@AfterEach
	void tearDown() {
		roomIds.forEach(roomId -> jdbcTemplate.update("delete from notifications where room_id = ?", roomId));
		roomIds
			.forEach(roomId -> jdbcTemplate.update("delete from notification_outbox_events where room_id = ?", roomId));
		chatRoomIds
			.forEach(chatRoomId -> jdbcTemplate.update("delete from chat_messages where chat_room_id = ?", chatRoomId));
		participationIds.forEach(participationRepository::deleteById);
		chatRoomIds.forEach(chatRoomId -> jdbcTemplate.update("delete from chat_rooms where id = ?", chatRoomId));
		roomIds.forEach(roomId -> jdbcTemplate.update("delete from rooms where id = ?", roomId));
		userIds.forEach(userId -> jdbcTemplate.update("delete from users where id = ?", userId));
		realtimePublisher.clear();
	}

	@Test
	void 신규_저장과_정규화된_동일_멱등_재전송은_메시지와_커밋_신호_하나로_수렴한다() {
		long hostUserId = insertUser("host");
		Room room = createChatRoom(hostUserId);

		ChatMessageSendResult first = chatMessageCommandService.send(
			hostUserId, room.getId(), new ChatMessageSendRequest("client-1", "  안녕하세요  "));
		ChatMessageSendResult replay = chatMessageCommandService.send(
			hostUserId, room.getId(), new ChatMessageSendRequest("client-1", "안녕하세요"));

		assertTrue(first.created());
		assertFalse(replay.created());
		assertEquals(first.message(), replay.message());
		assertEquals(1, chatMessageRepository.count());
		assertEquals("안녕하세요", first.message().content());
		assertEquals("host", first.message().sender().nickname());
		assertEquals(List.of(MessageCommitted.messageCreated(room.getId(), first.message().messageId())),
			realtimePublisher.events());
	}

	@Test
	void 다른_본문_또는_잘못된_본문은_저장과_커밋_신호_없이_VALIDATION_ERROR다() {
		long hostUserId = insertUser("host");
		Room room = createChatRoom(hostUserId);
		chatMessageCommandService.send(hostUserId, room.getId(), new ChatMessageSendRequest("client-1", "첫 본문"));
		realtimePublisher.clear();

		assertValidationError(hostUserId, room.getId(), new ChatMessageSendRequest("client-1", "다른 본문"));
		assertValidationError(hostUserId, room.getId(), new ChatMessageSendRequest("client-2", "   "));
		assertValidationError(hostUserId, room.getId(), new ChatMessageSendRequest(" ", "유효한 본문"));

		assertEquals(1, chatMessageRepository.count());
		assertTrue(realtimePublisher.events().isEmpty());
	}

	@Test
	void 제어문자가_포함된_본문은_저장_없이_VALIDATION_ERROR다() {
		long hostUserId = insertUser("host");
		Room room = createChatRoom(hostUserId);

		assertValidationError(hostUserId, room.getId(),
			new ChatMessageSendRequest("client-control", "본문\u0000끝"));
		assertValidationError(hostUserId, room.getId(),
			new ChatMessageSendRequest("client-control-2", "[31m본문"));

		assertEquals(0, chatMessageRepository.count());
		assertTrue(realtimePublisher.events().isEmpty());
	}

	@Test
	void T2_LF는_유지하고_CRLF는_LF로_정규화한_뒤_길이를_검증한다() {
		long hostUserId = insertUser("host");
		Room room = createChatRoom(hostUserId);

		ChatMessageSendResult lfResult = chatMessageCommandService.send(
			hostUserId, room.getId(), new ChatMessageSendRequest("line-feed", "첫 줄\n둘째 줄"));
		ChatMessageSendResult crlfResult = chatMessageCommandService.send(
			hostUserId, room.getId(), new ChatMessageSendRequest(
				"carriage-return-line-feed", "a".repeat(250) + "\r\n" + "b".repeat(249)));
		assertValidationError(hostUserId, room.getId(), new ChatMessageSendRequest(
			"too-long-after-normalization", "a".repeat(250) + "\r\n" + "b".repeat(250)));

		assertEquals("첫 줄\n둘째 줄", lfResult.message().content());
		assertEquals("a".repeat(250) + "\n" + "b".repeat(249), crlfResult.message().content());
		assertEquals(500, crlfResult.message().content().length());
		assertEquals(2, chatMessageRepository.count());
		assertEquals(2, realtimePublisher.events().size());
	}

	@Test
	void T4_같은_멱등성_키의_LF_CRLF_재전송은_하나로_수렴하고_다른_본문은_거절한다() {
		long hostUserId = insertUser("host");
		Room room = createChatRoom(hostUserId);

		ChatMessageSendResult first = chatMessageCommandService.send(
			hostUserId, room.getId(), new ChatMessageSendRequest("same-key", "첫 줄\n둘째 줄"));
		ChatMessageSendResult replay = chatMessageCommandService.send(
			hostUserId, room.getId(), new ChatMessageSendRequest("same-key", "첫 줄\r\n둘째 줄"));
		assertValidationError(hostUserId, room.getId(),
			new ChatMessageSendRequest("same-key", "첫 줄\n다른 둘째 줄"));

		assertTrue(first.created());
		assertFalse(replay.created());
		assertEquals(first.message(), replay.message());
		assertEquals("첫 줄\n둘째 줄", replay.message().content());
		assertEquals(1, chatMessageRepository.count());
		assertEquals(List.of(MessageCommitted.messageCreated(room.getId(), first.message().messageId())),
			realtimePublisher.events());
	}

	@Test
	void 스크립트나_HTML_형태의_본문도_그대로_저장되고_일반_텍스트로만_반환된다() {
		long hostUserId = insertUser("host");
		Room room = createChatRoom(hostUserId);
		String scriptContent = "<script>alert('xss')</script>";

		ChatMessageSendResult result = chatMessageCommandService.send(
			hostUserId, room.getId(), new ChatMessageSendRequest("client-script", scriptContent));

		assertTrue(result.created());
		assertEquals(scriptContent, result.message().content());
		assertEquals(scriptContent, chatMessageRepository.findAll().get(0).getContent());
	}

	@Test
	void 입력_최종_상태_ROOM과_취소_참가자는_저장과_커밋_신호_없이_계약_오류다() {
		long hostUserId = insertUser("host");
		long participantUserId = insertUser("participant");
		Room inputRoom = createChatRoom(hostUserId);
		Room canceledRoom = createChatRoom(hostUserId);
		assertTrue(canceledRoom.cancel());
		roomRepository.saveAndFlush(canceledRoom);
		Room finishedRoom = createChatRoom(hostUserId, NOW.minusSeconds(24 * 60 * 60));
		Room canceledParticipantRoom = createChatRoom(hostUserId);
		Participation canceledParticipation = participationRepository.saveAndFlush(
			Participation.createActive(canceledParticipantRoom, participantUserId, NOW));
		participationIds.add(canceledParticipation.getId());
		canceledParticipation.cancel(NOW.plusSeconds(1));
		participationRepository.saveAndFlush(canceledParticipation);

		assertValidationError(hostUserId, inputRoom.getId(), new ChatMessageSendRequest("i".repeat(101), "본문"));
		assertValidationError(hostUserId, inputRoom.getId(), new ChatMessageSendRequest(null, "본문"));
		assertValidationError(hostUserId, inputRoom.getId(), new ChatMessageSendRequest("null-content", null));
		assertValidationError(hostUserId, inputRoom.getId(), new ChatMessageSendRequest("empty-content", ""));
		assertValidationError(hostUserId, inputRoom.getId(),
			new ChatMessageSendRequest("long-content", "c".repeat(501)));
		assertError(ErrorCode.FORBIDDEN,
			() -> chatMessageCommandService.send(hostUserId, canceledRoom.getId(),
				new ChatMessageSendRequest("canceled", "본문")));
		assertError(ErrorCode.FORBIDDEN,
			() -> chatMessageCommandService.send(hostUserId, finishedRoom.getId(),
				new ChatMessageSendRequest("finished", "본문")));
		assertError(ErrorCode.FORBIDDEN,
			() -> chatMessageCommandService.send(
				participantUserId,
				canceledParticipantRoom.getId(),
				new ChatMessageSendRequest("participant-canceled", "본문")));

		assertEquals(0, chatMessageRepository.count());
		assertTrue(realtimePublisher.events().isEmpty());
	}

	@Test
	void 정확히_100자_멱등성_키와_500자_본문은_저장된다() {
		long hostUserId = insertUser("host");
		Room room = createChatRoom(hostUserId);

		ChatMessageSendResult result = chatMessageCommandService.send(
			hostUserId, room.getId(), new ChatMessageSendRequest("i".repeat(100), "c".repeat(500)));

		assertTrue(result.created());
		assertEquals(500, result.message().content().length());
		assertEquals(1, chatMessageRepository.count());
		assertEquals(1, realtimePublisher.events().size());
	}

	@Test
	void 사용자나_ROOM이_다르면_같은_멱등성_키도_독립적으로_저장된다() {
		long hostUserId = insertUser("host");
		long participantUserId = insertUser("participant");
		Room firstRoom = createChatRoom(hostUserId);
		Room secondRoom = createChatRoom(hostUserId);
		Participation participation = participationRepository.saveAndFlush(
			Participation.createActive(firstRoom, participantUserId, NOW));
		participationIds.add(participation.getId());

		ChatMessageSendRequest request = new ChatMessageSendRequest("client-1", "같은 키 본문");
		assertTrue(chatMessageCommandService.send(hostUserId, firstRoom.getId(), request).created());
		assertTrue(chatMessageCommandService.send(participantUserId, firstRoom.getId(), request).created());
		assertTrue(chatMessageCommandService.send(hostUserId, secondRoom.getId(), request).created());

		assertEquals(3, chatMessageRepository.count());
		assertEquals(3, realtimePublisher.events().size());
	}

	@Test
	void CLOSED_ROOM의_현재_ACTIVE_참가자도_메시지를_저장할_수_있다() {
		long hostUserId = insertUser("host");
		long participantUserId = insertUser("participant");
		Room room = createChatRoom(hostUserId, 1);
		participate(participantUserId, room.getId());

		ChatMessageSendResult result = chatMessageCommandService.send(
			participantUserId, room.getId(), new ChatMessageSendRequest("client-closed", "마감 뒤 본문"));

		assertTrue(result.created());
		assertEquals(1, chatMessageRepository.count());
	}

	@Test
	void 비관계자와_존재하지_않는_ROOM은_저장하지_않고_각각_403과_404다() {
		long hostUserId = insertUser("host");
		long strangerUserId = insertUser("stranger");
		Room room = createChatRoom(hostUserId);

		assertError(ErrorCode.FORBIDDEN,
			() -> chatMessageCommandService.send(strangerUserId, room.getId(),
				new ChatMessageSendRequest("client-1", "본문")));
		assertError(ErrorCode.ROOM_NOT_FOUND,
			() -> chatMessageCommandService.send(hostUserId, 999_999L, new ChatMessageSendRequest("client-1", "본문")));

		assertEquals(0, chatMessageRepository.count());
		assertTrue(realtimePublisher.events().isEmpty());
	}

	@Test
	void 롤백된_신규_저장은_AFTER_COMMIT_전달_신호를_만들지_않는다() {
		long hostUserId = insertUser("host");
		Room room = createChatRoom(hostUserId);

		transactionTemplate.executeWithoutResult(status -> {
			chatMessageCommandService.send(hostUserId, room.getId(), new ChatMessageSendRequest("client-1", "본문"));
			status.setRollbackOnly();
		});

		assertEquals(0, chatMessageRepository.count());
		assertTrue(realtimePublisher.events().isEmpty());
	}

	@Test
	void T2_채팅_rollback_only_통합은_미저장과_failed_한번_accepted_불변을_기록한다() {
		long hostUserId = insertUser("rollback-metric-host");
		Room room = createChatRoom(hostUserId);
		double acceptedBefore = operationCount("accepted");
		double failedBefore = operationCount("failed");

		transactionTemplate.executeWithoutResult(status -> {
			chatMessageCommandService.send(
				hostUserId, room.getId(), new ChatMessageSendRequest("rollback-metric", "본문"));
			status.setRollbackOnly();
		});

		assertEquals(0, chatMessageRepository.count());
		assertEquals(acceptedBefore, operationCount("accepted"));
		assertEquals(failedBefore + 1.0, operationCount("failed"));
		assertTrue(realtimePublisher.events().isEmpty());
	}

	private double operationCount(String outcome) {
		Counter counter = meterRegistry.find("chat.message.operations").tag("outcome", outcome).counter();
		return counter == null ? 0.0 : counter.count();
	}

	private void assertValidationError(long userId, long roomId, ChatMessageSendRequest request) {
		assertError(ErrorCode.VALIDATION_ERROR, () -> chatMessageCommandService.send(userId, roomId, request));
	}

	private void assertError(ErrorCode expected, org.junit.jupiter.api.function.Executable executable) {
		BusinessException exception = assertThrows(BusinessException.class, executable);
		assertEquals(expected, exception.getErrorCode());
	}

	private long insertUser(String nickname) {
		String email = "chat-message-" + UUID.randomUUID() + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?)",
			email,
			nickname,
			NOW,
			NOW);
		Long userId = jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
		userIds.add(userId);
		return userId;
	}

	private Room createChatRoom(long hostUserId) {
		return createChatRoom(hostUserId, 2);
	}

	private Room createChatRoom(long hostUserId, int capacity) {
		return createChatRoom(hostUserId, NOW.plusSeconds(3600), capacity);
	}

	private Room createChatRoom(long hostUserId, Instant startAt) {
		return createChatRoom(hostUserId, startAt, 2);
	}

	private Room createChatRoom(long hostUserId, Instant startAt, int capacity) {
		Room room = roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"메시지 전송 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				startAt,
				"홍대",
				capacity));
		roomIds.add(room.getId());
		ChatRoom chatRoom = chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));
		chatRoomIds.add(chatRoom.getId());
		return room;
	}

	private void participate(long userId, long roomId) {
		roomParticipationService.participate(userId, roomId);
		Participation participation = participationRepository.findByRoomIdAndUserId(roomId, userId).orElseThrow();
		participationIds.add(participation.getId());
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestBeans {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		@Bean
		@Primary
		RecordingChatRealtimePublisher recordingChatRealtimePublisher() {
			return new RecordingChatRealtimePublisher();
		}
	}

	static class RecordingChatRealtimePublisher implements ChatRealtimePublisher {

		private final List<MessageCommitted> events = new CopyOnWriteArrayList<>();

		@Override
		public void publish(MessageCommitted event) {
			events.add(event);
		}

		List<MessageCommitted> events() {
			return List.copyOf(events);
		}

		void clear() {
			events.clear();
		}
	}
}
