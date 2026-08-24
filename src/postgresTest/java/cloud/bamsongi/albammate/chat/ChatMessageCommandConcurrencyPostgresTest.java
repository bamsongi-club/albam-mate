package cloud.bamsongi.albammate.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.chat.contract.ChatRealtimePublisher;
import cloud.bamsongi.albammate.chat.contract.MessageCommitted;
import cloud.bamsongi.albammate.chat.dto.ChatMessageSendRequest;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.chat.service.ChatMessageCommandService;
import cloud.bamsongi.albammate.chat.service.ChatMessageSendResult;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationCancelService;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

@Testcontainers
@SpringBootTest
@Import(ChatMessageCommandConcurrencyPostgresTest.FixedClockConfiguration.class)
class ChatMessageCommandConcurrencyPostgresTest extends SharedPostgresIntegrationSupport {

	private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");
	private static final long WAIT_SECONDS = 10;

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
	@Autowired
	private RecordingChatRealtimePublisher realtimePublisher;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@Autowired
	private RoomParticipationService roomParticipationService;
	@Autowired
	private RoomParticipationCancelService roomParticipationCancelService;

	@AfterEach
	void tearDown() {
		realtimePublisher.clear();
		jdbcTemplate
			.execute("truncate table chat_messages, chat_rooms, participations, rooms, users restart identity cascade");
	}

	@Test
	void CHAT_ROOMS_쓰기_잠금은_같은_멱등성_키의_동시_전송을_하나의_메시지와_이벤트로_직렬화한다() throws Exception {
		long hostUserId = insertUser("host");
		Room room = createChatRoom(hostUserId);
		ChatMessageSendRequest request = new ChatMessageSendRequest("client-1", "동시 본문");
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<ChatMessageSendResult> first = executor
				.submit(() -> sendAfterStart(ready, start, hostUserId, room.getId(), request));
			Future<ChatMessageSendResult> second = executor
				.submit(() -> sendAfterStart(ready, start, hostUserId, room.getId(), request));
			assertTrue(ready.await(WAIT_SECONDS, TimeUnit.SECONDS));
			start.countDown();

			List<ChatMessageSendResult> results = List.of(
				first.get(WAIT_SECONDS, TimeUnit.SECONDS), second.get(WAIT_SECONDS, TimeUnit.SECONDS));
			assertEquals(1, results.stream().filter(ChatMessageSendResult::created).count());
			assertEquals(1, chatMessageRepository.count());
			assertEquals(results.getFirst().message(), results.getLast().message());
			assertEquals(
				List.of(MessageCommitted.messageCreated(room.getId(), results.getFirst().message().messageId())),
				realtimePublisher.events());
		} finally {
			start.countDown();
			shutdown(executor);
		}
	}

	@Test
	void ROOMS_공유_잠금을_얻은_실제_send는_그_뒤_CHAT_ROOMS_저장까지_상태_변경을_막는다() throws Exception {
		long hostUserId = insertUser("host");
		Room room = createChatRoom(hostUserId);
		CountDownLatch messageSaved = new CountDownLatch(1);
		CountDownLatch releaseMessageCommit = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<ChatMessageSendResult> send = executor.submit(
				() -> transactionTemplate().execute(status -> {
					ChatMessageSendResult result = chatMessageCommandService.send(
						hostUserId, room.getId(), new ChatMessageSendRequest("client-first", "먼저 저장된 본문"));
					messageSaved.countDown();
					await(releaseMessageCommit);
					return result;
				}));
			await(messageSaved);

			CountDownLatch cancelStarted = new CountDownLatch(1);
			Future<?> cancel = executor.submit(() -> {
				cancelStarted.countDown();
				cancelRoomInTransaction(room.getId(), new CountDownLatch(0), releaseMessageCommit);
			});
			assertCommandIsBlocked(cancel, cancelStarted);
			releaseMessageCommit.countDown();
			ChatMessageSendResult result = send.get(WAIT_SECONDS, TimeUnit.SECONDS);
			cancel.get(WAIT_SECONDS, TimeUnit.SECONDS);

			assertTrue(result.created());
			assertEquals(1, chatMessageRepository.count());
			assertForbidden(() -> chatMessageCommandService.send(
				hostUserId, room.getId(), new ChatMessageSendRequest("client-after", "취소 뒤 본문")));
			assertEquals(1, chatMessageRepository.count());
		} finally {
			releaseMessageCommit.countDown();
			shutdown(executor);
		}
	}

	@Test
	void ROOMS_다음_CHAT_ROOMS_잠금을_가진_ACTIVE_참가자_send는_실제_참가_취소를_커밋_전까지_막는다() throws Exception {
		long hostUserId = insertUser("host");
		long participantUserId = insertUser("participant");
		Room room = createChatRoom(hostUserId);
		roomParticipationService.participate(participantUserId, room.getId());
		CountDownLatch messageSaved = new CountDownLatch(1);
		CountDownLatch releaseMessageCommit = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<ChatMessageSendResult> send = executor.submit(
				() -> transactionTemplate().execute(status -> {
					ChatMessageSendResult result = chatMessageCommandService.send(
						participantUserId,
						room.getId(),
						new ChatMessageSendRequest("participant-first", "참가자 먼저 저장"));
					messageSaved.countDown();
					await(releaseMessageCommit);
					return result;
				}));
			await(messageSaved);

			CountDownLatch cancelStarted = new CountDownLatch(1);
			Future<?> cancel = executor.submit(() -> {
				cancelStarted.countDown();
				roomParticipationCancelService.cancelParticipation(participantUserId, room.getId());
			});
			assertCommandIsBlocked(cancel, cancelStarted);
			releaseMessageCommit.countDown();
			assertTrue(send.get(WAIT_SECONDS, TimeUnit.SECONDS).created());
			cancel.get(WAIT_SECONDS, TimeUnit.SECONDS);

			assertEquals(1, chatMessageRepository.count());
			assertForbidden(() -> chatMessageCommandService.send(
				participantUserId,
				room.getId(),
				new ChatMessageSendRequest("participant-after", "취소 뒤 본문")));
			assertEquals(1, chatMessageRepository.count());
		} finally {
			releaseMessageCommit.countDown();
			shutdown(executor);
		}
	}

	@Test
	void 실제_상태_변경이_ROOM_갱신을_flush한_뒤_시작한_send는_커밋_후_FORBIDDEN이다() throws Exception {
		long hostUserId = insertUser("host");
		Room room = createChatRoom(hostUserId);
		CountDownLatch cancelFlushed = new CountDownLatch(1);
		CountDownLatch releaseCancelCommit = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> cancel = executor.submit(
				() -> cancelRoomInTransaction(room.getId(), cancelFlushed, releaseCancelCommit));
			await(cancelFlushed);
			CountDownLatch sendStarted = new CountDownLatch(1);
			Future<ChatMessageSendResult> send = executor.submit(() -> {
				sendStarted.countDown();
				return chatMessageCommandService.send(
					hostUserId, room.getId(), new ChatMessageSendRequest("client-after", "취소 뒤 본문"));
			});
			assertCommandIsBlocked(send, sendStarted);
			releaseCancelCommit.countDown();
			cancel.get(WAIT_SECONDS, TimeUnit.SECONDS);

			assertFutureForbidden(send);
			assertEquals(0, chatMessageRepository.count());
			assertTrue(realtimePublisher.events().isEmpty());
		} finally {
			releaseCancelCommit.countDown();
			shutdown(executor);
		}
	}

	private ChatMessageSendResult sendAfterStart(
		CountDownLatch ready,
		CountDownLatch start,
		long userId,
		long roomId,
		ChatMessageSendRequest request) {
		ready.countDown();
		await(start);
		return chatMessageCommandService.send(userId, roomId, request);
	}

	private void assertForbidden(org.junit.jupiter.api.function.Executable executable) {
		BusinessException exception = assertThrows(BusinessException.class, executable);
		assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
	}

	private void assertFutureForbidden(Future<?> future) {
		ExecutionException exception = assertThrows(
			ExecutionException.class, () -> future.get(WAIT_SECONDS, TimeUnit.SECONDS));
		assertTrue(exception.getCause() instanceof BusinessException);
		assertEquals(ErrorCode.FORBIDDEN, ((BusinessException)exception.getCause()).getErrorCode());
	}

	private void assertCommandIsBlocked(Future<?> command, CountDownLatch started) {
		await(started);
		assertFalse(command.isDone(), "ROOM 공유 잠금이 유지되는 동안 상태 변경이 완료됐습니다.");
		assertThrows(TimeoutException.class, () -> command.get(1, TimeUnit.SECONDS));
	}

	private Room createChatRoom(long hostUserId) {
		Room room = roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"메시지 경합 방",
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

	private void cancelRoomInTransaction(long roomId, CountDownLatch flushed, CountDownLatch releaseCommit) {
		transactionTemplate().executeWithoutResult(status -> {
			Room room = roomRepository.findById(roomId).orElseThrow();
			assertTrue(room.cancel());
			roomRepository.flush();
			flushed.countDown();
			await(releaseCommit);
		});
	}

	private TransactionTemplate transactionTemplate() {
		return new TransactionTemplate(transactionManager);
	}

	private long insertUser(String role) {
		String email = "chat-message-" + role + "-" + UUID.randomUUID() + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?)",
			email,
			"채팅 " + role,
			Timestamp.from(NOW),
			Timestamp.from(NOW));
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	private void await(CountDownLatch latch) {
		try {
			assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "동시성 동기화 지점에 도달하지 못했습니다.");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("동시성 대기 중 인터럽트되었습니다.", exception);
		}
	}

	private void shutdown(ExecutorService executor) {
		executor.shutdown();
		try {
			if (!executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) {
				executor.shutdownNow();
				assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
			}
		} catch (InterruptedException exception) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
			throw new AssertionError("동시성 워커 종료 대기 중 인터럽트되었습니다.", exception);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfiguration {

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

		private final List<MessageCommitted> events = new java.util.concurrent.CopyOnWriteArrayList<>();

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
