package cloud.bamsongi.albammate.chat.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketSession;

import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** T1·T2·T3: 연결 레지스트리의 등록·해제, 방별 조회와 재연결 기준 계산을 직접 검증한다. */
class ChatConnectionRegistryTest {

	private static final long ROOM_ID = 7L;
	private static final long OTHER_ROOM_ID = 8L;
	private static final long CHAT_ROOM_ID = 99L;
	private static final long USER_ID = 42L;

	private final ChatRoomRepository chatRoomRepository = mock(ChatRoomRepository.class);
	private final ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
	private final ChatWebSocketMetrics metrics = new ChatWebSocketMetrics(new SimpleMeterRegistry());
	private final ChatConnectionRegistry registry = new ChatConnectionRegistry(chatRoomRepository,
		chatMessageRepository, metrics);

	@Test
	void T1_roomId나_userId_속성이_없으면_등록하지_않고_활성_연결_수도_증가하지_않는다() {
		WebSocketSession session = session(new HashMap<>());

		ChatRoomConnection connection = registry.register(session);

		assertNull(connection);
		assertEquals(0, metrics.activeConnectionCount());
	}

	@Test
	void T1_roomId의_ChatRoom이_없으면_등록하지_않는다() {
		when(chatRoomRepository.findByRoomId(ROOM_ID)).thenReturn(Optional.empty());
		WebSocketSession session = session(attributes(ROOM_ID, USER_ID, null));

		ChatRoomConnection connection = registry.register(session);

		assertNull(connection);
		assertEquals(0, metrics.activeConnectionCount());
	}

	@Test
	void T1_속성이_갖춰지면_등록하고_해제하면_활성_연결_수가_증감한다() {
		stubChatRoom();
		stubLatestMessageId(0L);
		WebSocketSession session = session(attributes(ROOM_ID, USER_ID, null));

		ChatRoomConnection connection = registry.register(session);

		assertNotNull(connection);
		assertEquals(1, metrics.activeConnectionCount());

		registry.unregister(session);

		assertEquals(0, metrics.activeConnectionCount());
	}

	@Test
	void T2_같은_방의_여러_연결을_방_ID로_함께_조회하고_다른_방_ID로는_조회하지_않는다() {
		stubChatRoom();
		stubLatestMessageId(0L);
		WebSocketSession sessionA = session(attributes(ROOM_ID, USER_ID, null));
		WebSocketSession sessionB = session(attributes(ROOM_ID, USER_ID + 1, null));

		ChatRoomConnection connectionA = registry.register(sessionA);
		ChatRoomConnection connectionB = registry.register(sessionB);

		Set<ChatRoomConnection> found = registry.findByRoomId(ROOM_ID);
		assertEquals(Set.of(connectionA, connectionB), found);
		assertTrue(registry.findByRoomId(OTHER_ROOM_ID).isEmpty());
	}

	@Test
	void T2_방의_마지막_연결이_해제되면_방_항목을_남기지_않는다() {
		stubChatRoom();
		stubLatestMessageId(0L);
		WebSocketSession sessionA = session(attributes(ROOM_ID, USER_ID, null));
		WebSocketSession sessionB = session(attributes(ROOM_ID, USER_ID + 1, null));
		registry.register(sessionA);
		registry.register(sessionB);

		registry.unregister(sessionA);
		assertEquals(1, registry.findByRoomId(ROOM_ID).size());

		registry.unregister(sessionB);
		assertTrue(registry.findByRoomId(ROOM_ID).isEmpty());
	}

	@Test
	void F2_마지막_연결_해제와_새_연결_등록이_경합해도_세션과_방_인덱스가_함께_유지된다() throws Exception {
		stubChatRoom();
		stubLatestMessageId(0L);
		WebSocketSession closingSession = session(attributes(ROOM_ID, USER_ID, null));
		WebSocketSession newSession = session(attributes(ROOM_ID, USER_ID + 1, null));
		ChatRoomConnection closingConnection = registry.register(closingSession);
		RaceControlledSet controlledConnections = new RaceControlledSet(closingConnection);
		roomIndex().put(ROOM_ID, controlledConnections);
		AtomicReference<ChatRoomConnection> registeredConnection = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch newRegistrationPrepared = new CountDownLatch(1);
		CountDownLatch releaseNewRegistration = new CountDownLatch(1);
		when(chatMessageRepository.findByChatRoomIdOrderByIdDesc(eq(CHAT_ROOM_ID), any()))
			.thenAnswer(invocation -> {
				newRegistrationPrepared.countDown();
				await(releaseNewRegistration);
				return List.of();
			});

		Thread unregistering = new Thread(() -> runSafely(() -> registry.unregister(closingSession), failure));
		unregistering.start();
		assertTrue(controlledConnections.awaitOldConnectionRemoval(), "unregister did not remove the old connection");

		Thread registering = new Thread(() -> runSafely(
			() -> registeredConnection.set(registry.register(newSession)), failure));
		registering.start();
		try {
			assertTrue(newRegistrationPrepared.await(5, TimeUnit.SECONDS), "new registration did not prepare");
			releaseNewRegistration.countDown();
			assertTrue(awaitBlocked(registering), "new registration must wait for room index cleanup");
		} finally {
			releaseNewRegistration.countDown();
			controlledConnections.allowOldRoomCleanup();
			join(unregistering);
			join(registering);
		}

		assertNull(failure.get());
		assertNotNull(registeredConnection.get());
		assertEquals(registeredConnection.get(), registry.find(newSession));
		assertEquals(Set.of(registeredConnection.get()), registry.findByRoomId(ROOM_ID));
		assertEquals(Map.of(ROOM_ID, Set.of(registeredConnection.get())), registry.snapshotByRoomId());
	}

	@Test
	void T3_현재_방에_존재하는_afterMessageId면_그_값을_기준으로_삼는다() {
		stubChatRoom();
		stubLatestMessageId(7L);
		when(chatMessageRepository.existsByIdAndChatRoomId(5L, CHAT_ROOM_ID)).thenReturn(true);
		WebSocketSession session = session(attributes(ROOM_ID, USER_ID, 5L));

		ChatRoomConnection connection = registry.register(session);

		assertEquals(5L, connection.lastDeliveredMessageId.get());
	}

	@Test
	void T3_현재_방에_없는_afterMessageId는_0으로_되돌린다() {
		stubChatRoom();
		stubLatestMessageId(7L);
		when(chatMessageRepository.existsByIdAndChatRoomId(3L, CHAT_ROOM_ID)).thenReturn(false);
		WebSocketSession session = session(attributes(ROOM_ID, USER_ID, 3L));

		ChatRoomConnection connection = registry.register(session);

		assertEquals(0L, connection.lastDeliveredMessageId.get());
	}

	@Test
	void T3_다른_방에_존재하지만_현재_방_최신_ID보다_큰_afterMessageId도_0으로_되돌린다() {
		stubChatRoom();
		stubLatestMessageId(7L);
		when(chatMessageRepository.existsByIdAndChatRoomId(20L, CHAT_ROOM_ID)).thenReturn(false);
		when(chatMessageRepository.existsById(20L)).thenReturn(true);
		WebSocketSession session = session(attributes(ROOM_ID, USER_ID, 20L));

		ChatRoomConnection connection = registry.register(session);

		assertEquals(0L, connection.lastDeliveredMessageId.get());
	}

	@Test
	void T3_아직_저장되지_않은_미래_afterMessageId는_현재_최신_ID로_제한한다() {
		stubChatRoom();
		stubLatestMessageId(7L);
		when(chatMessageRepository.existsByIdAndChatRoomId(20L, CHAT_ROOM_ID)).thenReturn(false);
		when(chatMessageRepository.existsById(20L)).thenReturn(false);
		WebSocketSession session = session(attributes(ROOM_ID, USER_ID, 20L));

		ChatRoomConnection connection = registry.register(session);

		assertEquals(7L, connection.lastDeliveredMessageId.get());
	}

	@Test
	void T3_afterMessageId가_없으면_현재_최신_ID를_기준으로_삼는다() {
		stubChatRoom();
		stubLatestMessageId(9L);
		WebSocketSession session = session(attributes(ROOM_ID, USER_ID, null));

		ChatRoomConnection connection = registry.register(session);

		assertEquals(9L, connection.lastDeliveredMessageId.get());
	}

	private void stubChatRoom() {
		ChatRoom chatRoom = mock(ChatRoom.class);
		when(chatRoom.getId()).thenReturn(CHAT_ROOM_ID);
		when(chatRoomRepository.findByRoomId(ROOM_ID)).thenReturn(Optional.of(chatRoom));
	}

	private void stubLatestMessageId(long latestMessageId) {
		if (latestMessageId == 0L) {
			when(chatMessageRepository.findByChatRoomIdOrderByIdDesc(eq(CHAT_ROOM_ID), any()))
				.thenReturn(List.of());
			return;
		}
		ChatMessage latest = mock(ChatMessage.class);
		when(latest.getId()).thenReturn(latestMessageId);
		when(chatMessageRepository.findByChatRoomIdOrderByIdDesc(CHAT_ROOM_ID, PageRequest.of(0, 1)))
			.thenReturn(List.of(latest));
	}

	private Map<String, Object> attributes(Long roomId, Long userId, Long afterMessageId) {
		Map<String, Object> attributes = new HashMap<>();
		if (roomId != null) {
			attributes.put(ChatWebSocketHandler.ROOM_ID_ATTRIBUTE, roomId);
		}
		if (userId != null) {
			attributes.put(ChatWebSocketHandler.USER_ID_ATTRIBUTE, userId);
		}
		if (afterMessageId != null) {
			attributes.put(ChatWebSocketHandler.AFTER_MESSAGE_ID_ATTRIBUTE, afterMessageId);
		}
		return attributes;
	}

	private WebSocketSession session(Map<String, Object> attributes) {
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.getAttributes()).thenReturn(attributes);
		return session;
	}

	@SuppressWarnings("unchecked")
	private Map<Long, Set<ChatRoomConnection>> roomIndex() {
		return (Map<Long, Set<ChatRoomConnection>>)ReflectionTestUtils.getField(registry, "connectionsByRoomId");
	}

	private void runSafely(ThrowingRunnable runnable, AtomicReference<Throwable> failure) {
		try {
			runnable.run();
		} catch (Throwable throwable) {
			failure.compareAndSet(null, throwable);
		}
	}

	private void join(Thread thread) throws InterruptedException {
		thread.join(5_000);
		assertFalse(thread.isAlive(), "concurrent registry operation did not finish");
	}

	private boolean awaitBlocked(Thread thread) throws InterruptedException {
		long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadlineNanos) {
			if (thread.getState() == Thread.State.BLOCKED) {
				return true;
			}
			if (!thread.isAlive()) {
				return false;
			}
			Thread.sleep(10);
		}
		return thread.getState() == Thread.State.BLOCKED;
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new AssertionError("concurrent registry operation timed out");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("concurrent registry operation was interrupted", exception);
		}
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	private static class RaceControlledSet extends AbstractSet<ChatRoomConnection> {

		private final Set<ChatRoomConnection> delegate = ConcurrentHashMap.newKeySet();
		private final ChatRoomConnection oldConnection;
		private final CountDownLatch oldConnectionRemoved = new CountDownLatch(1);
		private final CountDownLatch allowOldRoomCleanup = new CountDownLatch(1);

		private RaceControlledSet(ChatRoomConnection oldConnection) {
			this.oldConnection = oldConnection;
			delegate.add(oldConnection);
		}

		@Override
		public Iterator<ChatRoomConnection> iterator() {
			return delegate.iterator();
		}

		@Override
		public int size() {
			return delegate.size();
		}

		@Override
		public boolean remove(Object connection) {
			boolean removed = delegate.remove(connection);
			if (connection == oldConnection) {
				oldConnectionRemoved.countDown();
				await(allowOldRoomCleanup);
			}
			return removed;
		}

		private boolean awaitOldConnectionRemoval() throws InterruptedException {
			return oldConnectionRemoved.await(5, TimeUnit.SECONDS);
		}

		private void allowOldRoomCleanup() {
			allowOldRoomCleanup.countDown();
		}

	}
}
