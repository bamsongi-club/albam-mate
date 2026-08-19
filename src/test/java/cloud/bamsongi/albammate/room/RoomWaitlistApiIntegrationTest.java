package cloud.bamsongi.albammate.room;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.room.controller.RoomWaitlistController;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.entity.RoomWaitlist;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;
import cloud.bamsongi.albammate.room.service.command.RoomWaitlistCommandService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/** PART-04 HTTP 계약과 H2 저장 경계를 실제 보안 필터를 거쳐 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RoomWaitlistApiIntegrationTest.WaitlistFailureConfiguration.class)
class RoomWaitlistApiIntegrationTest {

	private static final Instant REQUEST_TIME = Instant.parse("2026-08-05T00:00:00Z");

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private RoomWaitlistRepository roomWaitlistRepository;
	@Autowired
	private RoomWaitlistCommandService roomWaitlistCommandService;
	@Autowired
	private RoomWaitlistController roomWaitlistController;
	@Autowired
	private ResponseReadFailureGate responseReadFailureGate;
	@Autowired
	private RoomStatusCorrectionFailureGate roomStatusCorrectionFailureGate;

	private long hostUserId;
	private long waitingUserId;
	private long roomId;

	@BeforeEach
	void setUp() {
		hostUserId = insertUser("waitlist-api-host@example.com");
		waitingUserId = insertUser("waitlist-api-user@example.com");
		roomId = insertClosedRoom(hostUserId);
	}

	@AfterEach
	void tearDown() {
		responseReadFailureGate.reset();
		roomStatusCorrectionFailureGate.reset();
		jdbcTemplate
			.update("delete from room_waitlists where room_id in (select id from rooms where title = '대기 API 테스트 방')");
		jdbcTemplate
			.update("delete from participations where room_id in (select id from rooms where title = '대기 API 테스트 방')");
		jdbcTemplate.update("delete from rooms where title = '대기 API 테스트 방'");
		jdbcTemplate.update("delete from users where nickname like 'waitlist-api-%'");
	}

	@Test
	void T1_세_대기_endpoint는_인증_CSRF와_응답_미디어_계약을_지킨다() throws Exception {
		assertUnsupportedMediaType(mockMvc.perform(
			post(waitlistPath()).with(authenticationFor(waitingUserId)).with(csrf()).content("unexpected body")));
		assertUnsupportedMediaType(mockMvc.perform(
			post(waitlistPath()).with(authenticationFor(waitingUserId)).with(csrf())
				.contentType(MediaType.TEXT_PLAIN)));
		assertUnsupportedMediaType(mockMvc.perform(
			post(waitlistPath()).with(authenticationFor(waitingUserId)).with(csrf())
				.header(HttpHeaders.TRANSFER_ENCODING, "chunked")));
		assertHeaderlessRequestBodyIsRejected();

		assertUnsupportedMediaType(
			mockMvc.perform(get(waitlistMePath()).with(authenticationFor(waitingUserId)).content("unexpected body")));
		assertUnsupportedMediaType(mockMvc.perform(
			get(waitlistMePath()).with(authenticationFor(waitingUserId)).contentType(MediaType.TEXT_PLAIN)));

		assertUnsupportedMediaType(mockMvc.perform(
			delete(waitlistMePath()).with(authenticationFor(waitingUserId)).with(csrf()).content("unexpected body")));
		assertUnsupportedMediaType(mockMvc.perform(
			delete(waitlistMePath()).with(authenticationFor(waitingUserId)).with(csrf())
				.contentType(MediaType.TEXT_PLAIN)));

		mockMvc.perform(post(waitlistPath()).with(authenticationFor(waitingUserId)).with(csrf()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value(201))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.data.queueOrder").doesNotExist());

		mockMvc.perform(get(waitlistMePath()).with(authenticationFor(waitingUserId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

		mockMvc.perform(get(waitlistMePath()).with(authenticationFor(waitingUserId)).accept(MediaType.TEXT_PLAIN))
			.andExpect(status().isNotAcceptable());

		mockMvc.perform(delete(waitlistMePath()).with(authenticationFor(waitingUserId)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

		mockMvc.perform(get(waitlistMePath())).andExpect(status().isUnauthorized());
		mockMvc.perform(post(waitlistPath()).with(csrf())).andExpect(status().isUnauthorized());
		mockMvc.perform(delete(waitlistMePath()).with(csrf())).andExpect(status().isUnauthorized());
		mockMvc.perform(post(waitlistPath()).with(authenticationFor(waitingUserId))).andExpect(status().isForbidden());
		mockMvc.perform(delete(waitlistMePath()).with(authenticationFor(waitingUserId)))
			.andExpect(status().isForbidden());
		mockMvc.perform(post(waitlistPath()).with(authenticationFor(waitingUserId)).with(csrf().useInvalidToken()))
			.andExpect(status().isForbidden());
		mockMvc.perform(delete(waitlistMePath()).with(authenticationFor(waitingUserId)).with(csrf().useInvalidToken()))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/rooms/not-a-number/waitlist/me").with(authenticationFor(waitingUserId)))
			.andExpect(status().isBadRequest());
		mockMvc.perform(put(waitlistPath()).with(authenticationFor(waitingUserId)).with(csrf()))
			.andExpect(status().isMethodNotAllowed());
	}

	@Test
	void T2_신규와_재신청은_새_순번을_중복_WAITING은_기존_순번을_반환한다() throws Exception {
		mockMvc.perform(post(waitlistPath()).with(authenticationFor(waitingUserId)).with(csrf()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.waitlistStatus").value("WAITING"))
			.andExpect(jsonPath("$.data.position").value(1));
		long queueOrder = jdbcTemplate.queryForObject(
			"select queue_order from room_waitlists where room_id = ? and user_id = ?", Long.class, roomId,
			waitingUserId);
		long roomVersion = jdbcTemplate.queryForObject("select version from rooms where id = ?", Long.class, roomId);

		mockMvc.perform(post(waitlistPath()).with(authenticationFor(waitingUserId)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.waitlistStatus").value("WAITING"))
			.andExpect(jsonPath("$.data.position").value(1));
		org.junit.jupiter.api.Assertions.assertEquals(queueOrder, jdbcTemplate.queryForObject(
			"select queue_order from room_waitlists where room_id = ? and user_id = ?", Long.class, roomId,
			waitingUserId));
		org.junit.jupiter.api.Assertions.assertEquals(
			roomVersion, jdbcTemplate.queryForObject("select version from rooms where id = ?", Long.class, roomId));
	}

	@Test
	void T2_CANCELED_관계는_새_마지막_순번으로_재신청한다() throws Exception {
		assertReapplication("CANCELED");
	}

	@Test
	void T2_PROMOTED_관계는_새_마지막_순번으로_재신청한다() throws Exception {
		assertReapplication("PROMOTED");
	}

	@Test
	void T3_주최자와_직접_참가_가능한_방은_대기_등록을_거절한다() throws Exception {
		mockMvc.perform(post(waitlistPath()).with(authenticationFor(hostUserId)).with(csrf()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ALREADY_PARTICIPATING"));

		jdbcTemplate.update("update rooms set capacity = 2, status = 'RECRUITING' where id = ?", roomId);
		assertWaitlistRegistrationRejected(waitingUserId, "WAITLIST_NOT_AVAILABLE");
	}

	@Test
	void T3_시작_시각에_도달했거나_종료된_방은_대기_등록을_거절한다() throws Exception {
		jdbcTemplate.update("update rooms set start_at = ? where id = ?", REQUEST_TIME, roomId);
		assertWaitlistRegistrationRejected(waitingUserId, "WAITLIST_NOT_AVAILABLE");

		Instant futureStartAt = Instant.now().plusSeconds(3600);
		jdbcTemplate.update("update rooms set start_at = ?, status = 'CANCELED' where id = ?", futureStartAt, roomId);
		assertWaitlistRegistrationRejected(waitingUserId, "WAITLIST_NOT_AVAILABLE");

		jdbcTemplate.update("update rooms set start_at = ?, status = 'FINISHED' where id = ?", futureStartAt, roomId);
		assertWaitlistRegistrationRejected(waitingUserId, "WAITLIST_NOT_AVAILABLE");
	}

	@Test
	void T3_ACTIVE_참가자는_대기_등록보다_먼저_참가_오류를_반환한다() throws Exception {
		insertActiveParticipation(roomId, waitingUserId);

		assertWaitlistRegistrationRejected(waitingUserId, "ALREADY_PARTICIPATING");
	}

	@Test
	void T3_EXPIRED와_ROOM_CANCELED_대기는_재신청을_거절한다() throws Exception {
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(roomId, waitingUserId, 10L, REQUEST_TIME));
		setWaitlistStatus("EXPIRED");
		assertWaitlistRegistrationRejected(waitingUserId, "WAITLIST_NOT_AVAILABLE");

		setWaitlistStatus("ROOM_CANCELED");
		assertWaitlistRegistrationRejected(waitingUserId, "WAITLIST_NOT_AVAILABLE");
	}

	@Test
	void T4_대기_활성화는_ROOM_claim과_대기행을_함께_확정한다() throws Exception {
		mockMvc.perform(post(waitlistPath()).with(authenticationFor(waitingUserId)).with(csrf()))
			.andExpect(status().isCreated());

		org.junit.jupiter.api.Assertions.assertEquals(
			1L, jdbcTemplate.queryForObject("select version from rooms where id = ?", Long.class, roomId));
		org.junit.jupiter.api.Assertions.assertEquals(
			1,
			jdbcTemplate.queryForObject("select count(*) from room_waitlists where room_id = ? and status = 'WAITING'",
				Integer.class, roomId));
	}

	@Test
	void T4_응답_조회가_실패하면_ROOM_claim과_WAITING_대기는_함께_롤백된다() {
		responseReadFailureGate.failResponseReadFor(waitingUserId);
		BusinessException exception = org.junit.jupiter.api.Assertions.assertThrows(
			BusinessException.class,
			() -> roomWaitlistCommandService.register(waitingUserId, roomId));
		org.junit.jupiter.api.Assertions.assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
		org.junit.jupiter.api.Assertions.assertInstanceOf(IllegalStateException.class, exception.getCause());

		org.junit.jupiter.api.Assertions.assertEquals(
			0L, jdbcTemplate.queryForObject("select version from rooms where id = ?", Long.class, roomId));
		org.junit.jupiter.api.Assertions.assertEquals(
			0, jdbcTemplate.queryForObject("select count(*) from room_waitlists where room_id = ?", Integer.class,
				roomId));
	}

	@Test
	void T6_본인_대기_조회는_WAITING에만_position을_노출하고_이력없음은_구분한다() throws Exception {
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(roomId, waitingUserId, 10L, REQUEST_TIME));

		mockMvc.perform(get(waitlistMePath()).with(authenticationFor(waitingUserId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.waitlistStatus").value("WAITING"))
			.andExpect(jsonPath("$.data.position").value(1))
			.andExpect(jsonPath("$.data.queueOrder").doesNotExist());

		long anotherUserId = insertUser("waitlist-api-no-history@example.com");
		mockMvc.perform(get(waitlistMePath()).with(authenticationFor(anotherUserId)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("WAITLIST_ENTRY_NOT_FOUND"));
	}

	@Test
	void T6_CANCELED_EXPIRED_ROOM_CANCELED_이력은_null_position으로_조회된다() throws Exception {
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(roomId, waitingUserId, 10L, REQUEST_TIME));

		assertTerminalWaitlistStatus("CANCELED");
		assertTerminalWaitlistStatus("EXPIRED");
		assertTerminalWaitlistStatus("ROOM_CANCELED");
	}

	@Test
	void T6_PROMOTED_이력은_null_position과_ROOM_상세_JOINED를_반환한다() throws Exception {
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(roomId, waitingUserId, 10L, REQUEST_TIME));
		setWaitlistStatus("PROMOTED");
		jdbcTemplate.update("update rooms set capacity = 2, active_participant_count = 2 where id = ?", roomId);
		insertActiveParticipation(roomId, waitingUserId);

		mockMvc.perform(get(waitlistMePath()).with(authenticationFor(waitingUserId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.waitlistStatus").value("PROMOTED"))
			.andExpect(jsonPath("$.data.position").value(nullValue()));

		mockMvc.perform(get("/api/rooms/" + roomId).with(authenticationFor(waitingUserId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.myRole").value("JOINED"));
	}

	@Test
	void T6_없는_방의_대기_조회는_ROOM_NOT_FOUND를_반환한다() throws Exception {
		mockMvc.perform(get("/api/rooms/" + Long.MAX_VALUE + "/waitlist/me").with(authenticationFor(waitingUserId)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
	}

	@Test
	void T6_상태_보정_충돌은_ROOM_CONCURRENT_MODIFICATION을_반환한다() throws Exception {
		roomStatusCorrectionFailureGate.failRoomLookupFor(roomId);

		mockMvc.perform(get(waitlistMePath()).with(authenticationFor(waitingUserId)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ROOM_CONCURRENT_MODIFICATION"));
	}

	@Test
	void T7_WAITING_대기만_취소하고_없는_대기는_계약_오류다() throws Exception {
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(roomId, waitingUserId, 10L, REQUEST_TIME));

		mockMvc.perform(delete(waitlistMePath()).with(authenticationFor(waitingUserId)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data").isEmpty());

		mockMvc.perform(delete(waitlistMePath()).with(authenticationFor(waitingUserId)).with(csrf()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("WAITLIST_ENTRY_NOT_FOUND"));
	}

	private String waitlistPath() {
		return "/api/rooms/" + roomId + "/waitlist";
	}

	private void assertUnsupportedMediaType(ResultActions resultActions) throws Exception {
		resultActions.andExpect(status().isUnsupportedMediaType())
			.andExpect(jsonPath("$.status").value(415))
			.andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
	}

	private void assertHeaderlessRequestBodyIsRejected() throws IOException {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setContent("unexpected body".getBytes(StandardCharsets.UTF_8));
		HttpServletRequest headerlessRequest = new HttpServletRequestWrapper(request) {

			@Override
			public long getContentLengthLong() {
				return -1L;
			}
		};

		BusinessException exception = org.junit.jupiter.api.Assertions.assertThrows(
			BusinessException.class,
			() -> roomWaitlistController.register(roomId, headerlessRequest));
		org.junit.jupiter.api.Assertions.assertEquals(ErrorCode.UNSUPPORTED_MEDIA_TYPE, exception.getErrorCode());
	}

	private void assertWaitlistRegistrationRejected(long userId, String errorCode) throws Exception {
		mockMvc.perform(post(waitlistPath()).with(authenticationFor(userId)).with(csrf()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(errorCode));
	}

	private void assertTerminalWaitlistStatus(String waitlistStatus) throws Exception {
		setWaitlistStatus(waitlistStatus);

		mockMvc.perform(get(waitlistMePath()).with(authenticationFor(waitingUserId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.waitlistStatus").value(waitlistStatus))
			.andExpect(jsonPath("$.data.position").value(nullValue()));
	}

	private void assertReapplication(String previousStatus) throws Exception {
		long precedingUserId = insertUser("waitlist-api-preceding@example.com");
		long precedingQueueOrder = roomWaitlistRepository.getNextQueueOrder();
		long originalQueueOrder = roomWaitlistRepository.getNextQueueOrder();
		roomWaitlistRepository
			.saveAndFlush(RoomWaitlist.create(roomId, precedingUserId, precedingQueueOrder, REQUEST_TIME));
		roomWaitlistRepository
			.saveAndFlush(RoomWaitlist.create(roomId, waitingUserId, originalQueueOrder, REQUEST_TIME));
		jdbcTemplate.update(
			"update room_waitlists set status = ? where room_id = ? and user_id = ?",
			previousStatus, roomId, waitingUserId);

		mockMvc.perform(post(waitlistPath()).with(authenticationFor(waitingUserId)).with(csrf()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value(201))
			.andExpect(jsonPath("$.data.waitlistStatus").value("WAITING"))
			.andExpect(jsonPath("$.data.position").value(2));

		long reactivatedQueueOrder = jdbcTemplate.queryForObject(
			"select queue_order from room_waitlists where room_id = ? and user_id = ?", Long.class, roomId,
			waitingUserId);
		org.junit.jupiter.api.Assertions.assertTrue(reactivatedQueueOrder > precedingQueueOrder);
		org.junit.jupiter.api.Assertions.assertTrue(reactivatedQueueOrder > originalQueueOrder);
		org.junit.jupiter.api.Assertions.assertEquals(
			1L, jdbcTemplate.queryForObject("select version from rooms where id = ?", Long.class, roomId));
	}

	private String waitlistMePath() {
		return waitlistPath() + "/me";
	}

	private void insertActiveParticipation(long targetRoomId, long userId) {
		jdbcTemplate.update("""
			insert into participations (room_id, user_id, status, joined_at, canceled_at, created_at, updated_at)
			values (?, ?, 'ACTIVE', ?, null, ?, ?)
			""", targetRoomId, userId, REQUEST_TIME, REQUEST_TIME, REQUEST_TIME);
	}

	private void setWaitlistStatus(String waitlistStatus) {
		jdbcTemplate.update(
			"update room_waitlists set status = ? where room_id = ? and user_id = ?",
			waitlistStatus, roomId, waitingUserId);
	}

	private RequestPostProcessor authenticationFor(long userId) {
		return authentication(new UsernamePasswordAuthenticationToken(
			new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
	}

	private long insertUser(String email) {
		String uniqueEmail = email.replace("@", "+" + System.nanoTime() + "@");
		String nickname = "waitlist-api-" + System.nanoTime();
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?)",
			uniqueEmail, nickname, REQUEST_TIME, REQUEST_TIME);
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, uniqueEmail);
	}

	private long insertClosedRoom(long hostId) {
		jdbcTemplate.update("""
			insert into rooms (
			    host_user_id, room_type, title, experience_level, is_rulemaster_led, region, capacity,
			    active_participant_count, start_at, place, status, version, created_at, updated_at)
			values (?, 'PERSON_FOCUSED', '대기 API 테스트 방', 'ALL_LEVELS', false, '홍대', 1, 1, ?, '테스트 장소',
			        'CLOSED', 0, ?, ?)
			""", hostId, Instant.now().plusSeconds(3600), REQUEST_TIME, REQUEST_TIME);
		return jdbcTemplate.queryForObject("select id from rooms where host_user_id = ?", Long.class, hostId);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class WaitlistFailureConfiguration {

		@Bean
		ResponseReadFailureGate responseReadFailureGate() {
			return new ResponseReadFailureGate();
		}

		@Bean
		RoomStatusCorrectionFailureGate roomStatusCorrectionFailureGate() {
			return new RoomStatusCorrectionFailureGate();
		}

		@Bean(name = "failureInjectingRoomWaitlistRepository")
		@Primary
		RoomWaitlistRepository failureInjectingRoomWaitlistRepository(
			@Qualifier("roomWaitlistRepository") RoomWaitlistRepository delegate,
			ResponseReadFailureGate responseReadFailureGate) {
			InvocationHandler handler = new ResponseReadFailureRepositoryInvocationHandler(delegate,
				responseReadFailureGate);
			return (RoomWaitlistRepository)Proxy.newProxyInstance(
				RoomWaitlistRepository.class.getClassLoader(),
				new Class<?>[] {RoomWaitlistRepository.class},
				handler);
		}

		@Bean(name = "failureInjectingRoomRepository")
		@Primary
		RoomRepository failureInjectingRoomRepository(
			@Qualifier("roomRepository") RoomRepository delegate,
			RoomStatusCorrectionFailureGate roomStatusCorrectionFailureGate) {
			InvocationHandler handler = new RoomStatusCorrectionRepositoryInvocationHandler(delegate,
				roomStatusCorrectionFailureGate);
			return (RoomRepository)Proxy.newProxyInstance(
				RoomRepository.class.getClassLoader(),
				new Class<?>[] {RoomRepository.class},
				handler);
		}
	}

	static final class ResponseReadFailureGate {

		private long userId = Long.MIN_VALUE;
		private int readCount;

		void failResponseReadFor(long targetUserId) {
			userId = targetUserId;
			readCount = 0;
		}

		void reset() {
			userId = Long.MIN_VALUE;
			readCount = 0;
		}

		void afterStateRead(Object[] arguments) {
			if (arguments != null && arguments.length == 2 && arguments[1] instanceof Long readUserId
				&& readUserId == userId) {
				readCount++;
				if (readCount == 2) {
					throw new IllegalStateException("대기 등록 응답 조회 실패");
				}
			}
		}
	}

	static final class RoomStatusCorrectionFailureGate {

		private long roomId = Long.MIN_VALUE;

		void failRoomLookupFor(long targetRoomId) {
			roomId = targetRoomId;
		}

		void reset() {
			roomId = Long.MIN_VALUE;
		}

		void beforeRoomLookup(Object[] arguments) {
			if (arguments != null && arguments.length == 1 && arguments[0] instanceof Long lookupRoomId
				&& lookupRoomId == roomId) {
				throw new ObjectOptimisticLockingFailureException(Room.class, lookupRoomId);
			}
		}
	}

	private static final class ResponseReadFailureRepositoryInvocationHandler implements InvocationHandler {

		private final RoomWaitlistRepository delegate;
		private final ResponseReadFailureGate responseReadFailureGate;

		private ResponseReadFailureRepositoryInvocationHandler(
			RoomWaitlistRepository delegate, ResponseReadFailureGate responseReadFailureGate) {
			this.delegate = delegate;
			this.responseReadFailureGate = responseReadFailureGate;
		}

		@Override
		public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
			try {
				Object result = method.invoke(delegate, arguments);
				if (method.getName().equals("findStateWithPositionByRoomIdAndUserId")) {
					responseReadFailureGate.afterStateRead(arguments);
				}
				return result;
			} catch (InvocationTargetException exception) {
				throw exception.getCause();
			}
		}
	}

	private static final class RoomStatusCorrectionRepositoryInvocationHandler implements InvocationHandler {

		private final RoomRepository delegate;
		private final RoomStatusCorrectionFailureGate roomStatusCorrectionFailureGate;

		private RoomStatusCorrectionRepositoryInvocationHandler(
			RoomRepository delegate, RoomStatusCorrectionFailureGate roomStatusCorrectionFailureGate) {
			this.delegate = delegate;
			this.roomStatusCorrectionFailureGate = roomStatusCorrectionFailureGate;
		}

		@Override
		public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
			if (method.getName().equals("findByIdForWrite")) {
				roomStatusCorrectionFailureGate.beforeRoomLookup(arguments);
			}
			try {
				return method.invoke(delegate, arguments);
			} catch (InvocationTargetException exception) {
				throw exception.getCause();
			}
		}
	}
}
