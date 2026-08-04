package cloud.bamsongi.albammate.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.chat.dto.ChatMessagePageResponse;
import cloud.bamsongi.albammate.chat.dto.ChatMessageResponse;
import cloud.bamsongi.albammate.chat.dto.ChatMessageSendRequest;
import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.chat.service.ChatMessageCommandService;
import cloud.bamsongi.albammate.chat.service.ChatMessageHistoryQueryService;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

/** ADR-0031 커서 이력 조회가 동시 저장 중에도 이어 읽기에서 중복·누락이 없는지 실제 PostgreSQL로 검증한다. */
@Testcontainers
@SpringBootTest
@Import(ChatMessageHistoryCursorConcurrencyPostgresTest.FixedClockConfiguration.class)
class ChatMessageHistoryCursorConcurrencyPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");
	private static final long WAIT_SECONDS = 10;
	private static final int PAGE_SIZE = 20;
	private static final int INITIAL_MESSAGE_COUNT = 60;
	private static final int CONCURRENT_MESSAGE_COUNT = 20;

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_chat_history_cursor_concurrency_test");

	@Autowired
	private ChatMessageHistoryQueryService chatMessageHistoryQueryService;
	@Autowired
	private ChatMessageCommandService chatMessageCommandService;
	@Autowired
	private ChatMessageRepository chatMessageRepository;
	@Autowired
	private ChatRoomRepository chatRoomRepository;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void tearDown() {
		jdbcTemplate
			.execute("truncate table chat_messages, chat_rooms, participations, rooms, users restart identity cascade");
	}

	@Test
	void 메시지가_계속_저장되는_동안_nextBeforeMessageId로_이어_읽어도_중복과_누락이_없다() throws Exception {
		long hostUserId = insertUser("host");
		Room room = createChatRoom(hostUserId);
		List<Long> initialMessageIds = insertInitialMessages(room.getId(), hostUserId, INITIAL_MESSAGE_COUNT);

		ChatMessagePageResponse firstPage = chatMessageHistoryQueryService
			.history(hostUserId, room.getId(), null, PAGE_SIZE);

		ExecutorService writerExecutor = Executors.newFixedThreadPool(4);
		try {
			List<Future<?>> writers = new ArrayList<>();
			for (int i = 0; i < CONCURRENT_MESSAGE_COUNT; i++) {
				String clientMessageId = "concurrent-" + i;
				writers.add(
					writerExecutor.submit(
						() -> chatMessageCommandService.send(
							hostUserId, room.getId(), new ChatMessageSendRequest(clientMessageId, "동시 저장 본문"))));
			}

			for (Future<?> writer : writers) {
				writer.get(WAIT_SECONDS, TimeUnit.SECONDS);
			}
			assertEquals(
				(long)(INITIAL_MESSAGE_COUNT + CONCURRENT_MESSAGE_COUNT),
				chatMessageRepository.count(),
				"이어 읽기 전에 동시 저장이 커밋되어 페이지 경계 사이 삽입 상태가 만들어져야 합니다.");

			List<Long> collectedIds = new ArrayList<>(idsOf(firstPage));
			ChatMessagePageResponse page = firstPage;
			while (page.hasNext()) {
				page = chatMessageHistoryQueryService
					.history(hostUserId, room.getId(), page.nextBeforeMessageId(), PAGE_SIZE);
				collectedIds.addAll(idsOf(page));
			}

			Set<Long> uniqueCollectedIds = new LinkedHashSet<>(collectedIds);
			assertEquals(collectedIds.size(), uniqueCollectedIds.size(), "이어 읽은 구간에 중복 messageId가 있습니다.");
			assertEquals(new LinkedHashSet<>(initialMessageIds), uniqueCollectedIds);
		} finally {
			writerExecutor.shutdown();
			if (!writerExecutor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) {
				writerExecutor.shutdownNow();
				assertTrue(
					writerExecutor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS),
					"writer executor가 종료되지 않아 테스트 워커가 남았습니다.");
			}
		}
	}

	private List<Long> idsOf(ChatMessagePageResponse page) {
		return page.messages().stream().map(ChatMessageResponse::messageId).toList();
	}

	private List<Long> insertInitialMessages(long roomId, long senderUserId, int count) {
		long chatRoomInternalId = chatRoomRepository.findByRoomId(roomId).orElseThrow().getId();
		List<Long> messageIds = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			ChatMessage saved = chatMessageRepository.save(
				ChatMessage.create(
					chatRoomInternalId, senderUserId, "initial-" + i, "초기 본문 " + i, NOW.plusSeconds(i)));
			messageIds.add(saved.getId());
		}
		return messageIds;
	}

	private Room createChatRoom(long hostUserId) {
		Room room = roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"이력 커서 동시성 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				NOW.plusSeconds(3600),
				"홍대",
				2));
		chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));
		return room;
	}

	private long insertUser(String role) {
		String email = "chat-history-" + role + "-" + UUID.randomUUID() + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?)",
			email,
			"채팅 " + role,
			Timestamp.from(NOW),
			Timestamp.from(NOW));
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
