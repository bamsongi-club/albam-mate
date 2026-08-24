package cloud.bamsongi.albammate.chat.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.chat.system.ChatMessageResponseAssembler;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationCancelService;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;
import cloud.bamsongi.albammate.user.contract.UserQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * #870 T1·T3 — 실시간·재연결 catch-up 전달이 이력과 같은 mapper로 같은 문장을 만들고, {@code sender_user_id}가
 * {@code NULL}인 SYSTEM 행에서도 연결을 닫지 않고 두 종류를 하나의 오름차순 구간으로 복구함을 실제 PostgreSQL로
 * 재현한다.
 */
@SpringBootTest(classes = AlbamMateApplication.class)
@Import(ChatMessageDeliveryAssemblyPostgresTest.FixedClockConfiguration.class)
class ChatMessageDeliveryAssemblyPostgresTest extends SharedPostgresIntegrationSupport {

	private static final String GATE_NAME = "chat-system-message";
	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

	@Autowired
	private RoomParticipationService roomParticipationService;
	@Autowired
	private RoomParticipationCancelService roomParticipationCancelService;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private ChatRoomRepository chatRoomRepository;
	@Autowired
	private ChatMessageRepository chatMessageRepository;
	@Autowired
	private UserQuery userQuery;
	@Autowired
	private ChatMessageResponseAssembler responseAssembler;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final JsonMapper objectMapper = JsonMapper.builder().build();

	@Test
	void T3_재연결_catch_up이_USER와_SYSTEM을_하나의_오름차순_구간으로_중복_누락_없이_복구하고_연결을_닫지_않는다() throws Exception {
		activateGate();
		long hostUserId = insertUser("delivery-host@example.com", "방장");
		long participantUserId = insertUser("delivery-member@example.com", "참가자");
		Room room = createRoom(hostUserId, 2);
		Long chatRoomId = chatRoomRepository.findByRoomId(room.getId()).orElseThrow().getId();

		roomParticipationService.participate(participantUserId, room.getId());
		insertUserMessage(chatRoomId, hostUserId, "delivery-client-1", "안녕하세요");
		roomParticipationCancelService.cancelParticipation(participantUserId, room.getId());

		WebSocketSession session = mock(WebSocketSession.class);
		when(session.isOpen()).thenReturn(true);
		ChatConnectionRegistry connectionRegistry = mock(ChatConnectionRegistry.class);
		when(connectionRegistry.shouldStopDelivery(session)).thenReturn(false);
		ChatWebSocketMetrics metrics = new ChatWebSocketMetrics(new SimpleMeterRegistry());
		ChatMessageDeliveryService deliveryService = new ChatMessageDeliveryService(
			connectionRegistry, chatMessageRepository, userQuery, responseAssembler, metrics,
			objectMapper, Clock.fixed(NOW.plusSeconds(7200), ZoneOffset.UTC));
		ChatRoomConnection connection = new ChatRoomConnection(session, room.getId(), chatRoomId, hostUserId, 0L);

		deliveryService.deliverNewMessages(connection);

		verify(connectionRegistry, never()).closeForTransportFailure(any());
		ArgumentCaptor<TextMessage> sentCaptor = ArgumentCaptor.forClass(TextMessage.class);
		verify(session, times(3)).sendMessage(sentCaptor.capture());
		List<JsonNode> events = sentCaptor.getAllValues().stream()
			.map(text -> objectMapper.readTree(text.getPayload()))
			.toList();

		JsonNode entered = events.get(0).get("message");
		assertEquals("SYSTEM", entered.get("messageType").asText());
		assertEquals("PARTICIPANT_ENTERED", entered.get("systemEvent").asText());
		assertEquals("참가자님이 입장했어요.", entered.get("content").asText());
		assertTrue(entered.get("sender").isNull());
		assertFalse(entered.get("isMine").asBoolean());

		JsonNode userMessage = events.get(1).get("message");
		assertEquals("USER", userMessage.get("messageType").asText());
		assertEquals("안녕하세요", userMessage.get("content").asText());

		JsonNode left = events.get(2).get("message");
		assertEquals("PARTICIPANT_LEFT", left.get("systemEvent").asText());
		assertEquals("참가자님이 나갔어요.", left.get("content").asText());

		long lastMessageId = chatMessageRepository
			.findByChatRoomIdOrderByIdDesc(chatRoomId, org.springframework.data.domain.Pageable.unpaged())
			.getFirst()
			.getId();
		assertEquals(lastMessageId, connection.lastDeliveredMessageId.get());
	}

	@Test
	void T3_실시간_전달_중_전송_실패로_연결이_닫혀도_재연결_catch_up이_중단된_지점부터_중복_누락_없이_복구한다() throws Exception {
		activateGate();
		long hostUserId = insertUser("delivery-recover-host@example.com", "방장");
		long participantUserId = insertUser("delivery-recover-member@example.com", "참가자");
		Room room = createRoom(hostUserId, 2);
		Long chatRoomId = chatRoomRepository.findByRoomId(room.getId()).orElseThrow().getId();

		roomParticipationService.participate(participantUserId, room.getId());
		insertUserMessage(chatRoomId, hostUserId, "recover-client-1", "안녕하세요");
		roomParticipationCancelService.cancelParticipation(participantUserId, room.getId());

		WebSocketSession failingSession = mock(WebSocketSession.class);
		when(failingSession.isOpen()).thenReturn(true);
		doNothing().doThrow(new IOException("transport failure")).when(failingSession).sendMessage(any());
		ChatConnectionRegistry connectionRegistry = mock(ChatConnectionRegistry.class);
		when(connectionRegistry.shouldStopDelivery(any())).thenReturn(false);
		ChatWebSocketMetrics metrics = new ChatWebSocketMetrics(new SimpleMeterRegistry());
		ChatMessageDeliveryService deliveryService = new ChatMessageDeliveryService(
			connectionRegistry, chatMessageRepository, userQuery, responseAssembler, metrics,
			objectMapper, Clock.fixed(NOW.plusSeconds(7200), ZoneOffset.UTC));
		ChatRoomConnection firstConnection = new ChatRoomConnection(
			failingSession, room.getId(), chatRoomId, hostUserId, 0L);

		deliveryService.deliverNewMessages(firstConnection);

		verify(connectionRegistry, times(1)).closeForTransportFailure(failingSession);
		long deliveredBeforeFailure = firstConnection.lastDeliveredMessageId.get();
		assertTrue(deliveredBeforeFailure > 0, "첫 안내는 전송에 성공해 기준이 그 메시지까지 전진해야 한다");

		WebSocketSession reconnectedSession = mock(WebSocketSession.class);
		when(reconnectedSession.isOpen()).thenReturn(true);
		ChatRoomConnection reconnectedConnection = new ChatRoomConnection(
			reconnectedSession, room.getId(), chatRoomId, hostUserId, deliveredBeforeFailure);

		deliveryService.deliverNewMessages(reconnectedConnection);

		verify(connectionRegistry, never()).closeForTransportFailure(reconnectedSession);
		ArgumentCaptor<TextMessage> recoveredCaptor = ArgumentCaptor.forClass(TextMessage.class);
		verify(reconnectedSession, times(2)).sendMessage(recoveredCaptor.capture());
		List<JsonNode> recovered = recoveredCaptor.getAllValues().stream()
			.map(text -> objectMapper.readTree(text.getPayload()))
			.toList();

		JsonNode userMessage = recovered.get(0).get("message");
		assertEquals("USER", userMessage.get("messageType").asText());
		assertEquals("안녕하세요", userMessage.get("content").asText());
		JsonNode left = recovered.get(1).get("message");
		assertEquals("PARTICIPANT_LEFT", left.get("systemEvent").asText());
		assertEquals("참가자님이 나갔어요.", left.get("content").asText());

		long lastMessageId = chatMessageRepository
			.findByChatRoomIdOrderByIdDesc(chatRoomId, org.springframework.data.domain.Pageable.unpaged())
			.getFirst()
			.getId();
		assertEquals(lastMessageId, reconnectedConnection.lastDeliveredMessageId.get(), "복구 후 기준이 마지막 메시지까지 전진한다");
	}

	private void insertUserMessage(long chatRoomId, long senderUserId, String clientMessageId, String content) {
		jdbcTemplate.update(
			"insert into chat_messages (chat_room_id, sender_user_id, client_message_id, content, message_type, "
				+ "created_at) values (?, ?, ?, ?, 'USER', current_timestamp)",
			chatRoomId, senderUserId, clientMessageId, content);
	}

	private void activateGate() {
		jdbcTemplate.update(
			"update chat_system_message_activation set enabled_at = ?, updated_at = current_timestamp "
				+ "where gate_name = ?",
			Timestamp.from(NOW.minusSeconds(3600)), GATE_NAME);
	}

	private Room createRoom(long hostUserId, int capacity) {
		Room room = roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"CHAT-06 전달 조립 테스트 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				NOW.plusSeconds(3600),
				"홍대 장소",
				capacity));
		chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));
		return room;
	}

	private long insertUser(String email, String nickname) {
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'fixture-password-hash', ?, current_timestamp, current_timestamp)",
			email, nickname);
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfiguration {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}
}
