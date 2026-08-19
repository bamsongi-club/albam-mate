package cloud.bamsongi.albammate.chat.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.entity.ChatSystemEventKey;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationCancelService;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;

/**
 * #869 T4 — 같은 방의 동시 참가 경합에서 낙관 락 재시도 뒤에도 저장된 SYSTEM 안내 수와 최종 참가 관계가 정확히 일치함을
 * 실제 PostgreSQL로 재현한다.
 */
@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
@Import(ChatSystemMessageConcurrencyPostgresTest.FixedClockConfiguration.class)
class ChatSystemMessageConcurrencyPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final String GATE_NAME = "chat-system-message";
	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
	private static final int CONCURRENT_PARTICIPANTS = 8;

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_chat_system_message_concurrency_test");

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
	private JdbcTemplate jdbcTemplate;

	@Test
	void T4_같은_방의_동시_참가_경합에서_저장된_SYSTEM_안내_수와_최종_참가_인원이_일치한다() throws Exception {
		activateGate();
		long hostUserId = insertUser("concurrency-host@example.com");
		Room room = createRoom(hostUserId, CONCURRENT_PARTICIPANTS, NOW.plusSeconds(3600));
		Long chatRoomId = chatRoomRepository.findByRoomId(room.getId()).orElseThrow().getId();
		List<Long> participantUserIds = new ArrayList<>();
		for (int i = 0; i < CONCURRENT_PARTICIPANTS; i++) {
			participantUserIds.add(insertUser("concurrency-member-" + i + "@example.com"));
		}

		ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_PARTICIPANTS);
		CountDownLatch ready = new CountDownLatch(CONCURRENT_PARTICIPANTS);
		CountDownLatch start = new CountDownLatch(1);
		List<Long> succeededUserIds = java.util.Collections.synchronizedList(new ArrayList<>());
		try {
			List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
			for (Long participantUserId : participantUserIds) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					awaitUninterruptibly(start);
					roomParticipationService.participate(participantUserId, room.getId());
					succeededUserIds.add(participantUserId);
				}));
			}
			ready.await(10, TimeUnit.SECONDS);
			start.countDown();
			for (java.util.concurrent.Future<?> future : futures) {
				awaitAcceptingConcurrentModification(future);
			}
		} finally {
			executor.shutdown();
		}

		// 실제 PostgreSQL 행 잠금 경합에서는 재시도 상한(3회)을 넘는 요청이 ROOM_CONCURRENT_MODIFICATION으로
		// 정상 실패할 수 있다. T4는 그 실패를 포함해 "성공한 만큼만" 안내 수와 참가 인원이 정확히 일치하는지 본다.
		Room reloadedRoom = roomRepository.findById(room.getId()).orElseThrow();
		List<ChatMessage> systemMessages = chatMessageRepository
			.findByChatRoomIdOrderByIdDesc(chatRoomId, Pageable.unpaged())
			.stream()
			.filter(message -> message.getSystemEventKey() == ChatSystemEventKey.PARTICIPANT_ENTERED)
			.toList();
		Set<Long> subjectUserIds = systemMessages.stream().map(ChatMessage::getSubjectUserId)
			.collect(Collectors.toSet());

		assertTrue(succeededUserIds.size() > 0, "적어도 한 명은 경합 없이 성공해야 한다");
		assertEquals(succeededUserIds.size(), reloadedRoom.getActiveParticipantCount());
		assertEquals(succeededUserIds.size(), systemMessages.size());
		assertEquals(new HashSet<>(succeededUserIds), subjectUserIds);
	}

	@Test
	void T4_같은_방의_동시_취소_경합에서도_저장된_LEFT_안내_수와_최종_참가_인원_감소가_일치하고_교착이_없다() throws Exception {
		activateGate();
		long hostUserId = insertUser("cancel-concurrency-host@example.com");
		Room room = createRoom(hostUserId, CONCURRENT_PARTICIPANTS, NOW.plusSeconds(3600));
		Long chatRoomId = chatRoomRepository.findByRoomId(room.getId()).orElseThrow().getId();
		List<Long> participantUserIds = new ArrayList<>();
		for (int i = 0; i < CONCURRENT_PARTICIPANTS; i++) {
			long participantUserId = insertUser("cancel-concurrency-member-" + i + "@example.com");
			roomParticipationService.participate(participantUserId, room.getId());
			participantUserIds.add(participantUserId);
		}

		ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_PARTICIPANTS);
		CountDownLatch ready = new CountDownLatch(CONCURRENT_PARTICIPANTS);
		CountDownLatch start = new CountDownLatch(1);
		List<Long> canceledUserIds = java.util.Collections.synchronizedList(new ArrayList<>());
		try {
			List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
			for (Long participantUserId : participantUserIds) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					awaitUninterruptibly(start);
					roomParticipationCancelService.cancelParticipation(participantUserId, room.getId());
					canceledUserIds.add(participantUserId);
				}));
			}
			ready.await(10, TimeUnit.SECONDS);
			start.countDown();
			for (java.util.concurrent.Future<?> future : futures) {
				awaitAcceptingConcurrentModification(future);
			}
		} finally {
			executor.shutdown();
		}

		// ROOMS -> CHAT_ROOMS 잠금 순서가 유지되면 동시 취소는 순차 직렬화로만 지연되고 새 교착을 만들지 않는다.
		// awaitAcceptingConcurrentModification이 ROOM_CONCURRENT_MODIFICATION 외의 예외(교착·timeout 포함)는
		// 그대로 실패시키므로, 여기까지 도달했다는 것 자체가 그 부재를 재현한다.
		Room reloadedRoom = roomRepository.findById(room.getId()).orElseThrow();
		List<ChatMessage> leftMessages = chatMessageRepository
			.findByChatRoomIdOrderByIdDesc(chatRoomId, Pageable.unpaged())
			.stream()
			.filter(message -> message.getSystemEventKey() == ChatSystemEventKey.PARTICIPANT_LEFT)
			.toList();
		Set<Long> subjectUserIds = leftMessages.stream().map(ChatMessage::getSubjectUserId)
			.collect(Collectors.toSet());

		assertTrue(canceledUserIds.size() > 0, "적어도 한 명은 경합 없이 취소에 성공해야 한다");
		assertEquals(CONCURRENT_PARTICIPANTS - canceledUserIds.size(), reloadedRoom.getActiveParticipantCount());
		assertEquals(canceledUserIds.size(), leftMessages.size());
		assertEquals(new HashSet<>(canceledUserIds), subjectUserIds);
	}

	/** ROOM_CONCURRENT_MODIFICATION(재시도 상한 초과)만 정상 실패로 흡수하고, 그 외 예외는 테스트를 실패시킨다. */
	private void awaitAcceptingConcurrentModification(java.util.concurrent.Future<?> future) throws Exception {
		try {
			future.get(30, TimeUnit.SECONDS);
		} catch (java.util.concurrent.ExecutionException exception) {
			if (!(exception
				.getCause() instanceof cloud.bamsongi.albammate.global.exception.BusinessException businessException)
				|| businessException
					.getErrorCode() != cloud.bamsongi.albammate.global.exception.ErrorCode.ROOM_CONCURRENT_MODIFICATION) {
				throw new AssertionError("concurrent participation failed", exception);
			}
		}
	}

	private void awaitUninterruptibly(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(exception);
		}
	}

	private void activateGate() {
		jdbcTemplate.update(
			"update chat_system_message_activation set enabled_at = ?, updated_at = current_timestamp "
				+ "where gate_name = ?",
			Timestamp.from(NOW.minusSeconds(3600)),
			GATE_NAME);
	}

	private Room createRoom(long hostUserId, int capacity, Instant startAt) {
		Room room = roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"CHAT-06 동시성 테스트 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				startAt,
				"홍대 장소",
				capacity));
		chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));
		return room;
	}

	private long insertUser(String email) {
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'fixture-password-hash', '동시성 참가자', current_timestamp, current_timestamp)",
			email);
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
