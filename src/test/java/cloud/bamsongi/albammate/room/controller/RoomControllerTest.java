package cloud.bamsongi.albammate.room.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.global.config.SecurityConfig;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;
import cloud.bamsongi.albammate.global.response.PageResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.global.security.currentuser.SecurityContextCurrentUserAccessor;
import cloud.bamsongi.albammate.global.security.error.ApiAccessDeniedHandler;
import cloud.bamsongi.albammate.global.security.error.ApiAuthenticationEntryPoint;
import cloud.bamsongi.albammate.global.security.error.SecurityErrorResponseWriter;
import cloud.bamsongi.albammate.room.dto.CreateRoomRequest;
import cloud.bamsongi.albammate.room.dto.NicknameSummary;
import cloud.bamsongi.albammate.room.dto.ParticipantRoomResponse;
import cloud.bamsongi.albammate.room.dto.PublicRoomResponse;
import cloud.bamsongi.albammate.room.dto.RoomListRequest;
import cloud.bamsongi.albammate.room.dto.RoomStatusResponse;
import cloud.bamsongi.albammate.room.dto.RoomUpdateRequest;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.MyRole;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.service.command.RoomCreateService;
import cloud.bamsongi.albammate.room.service.command.RoomStatusChangeService;
import cloud.bamsongi.albammate.room.service.command.RoomUpdateService;
import cloud.bamsongi.albammate.room.service.query.RoomDetailService;
import cloud.bamsongi.albammate.room.service.query.RoomListQueryService;

@WebMvcTest(controllers = RoomController.class)
@Import({
	SecurityConfig.class,
	ApiAccessDeniedHandler.class,
	ApiAuthenticationEntryPoint.class,
	SecurityErrorResponseWriter.class,
	GlobalExceptionHandler.class,
	SecurityContextCurrentUserAccessor.class,
	RoomControllerTest.TestBeans.class
})
class RoomControllerTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private RoomCreateService roomCreateService;
	@Autowired
	private RoomListQueryService roomListQueryService;

	@BeforeEach
	void resetRoomListQueryService() {
		reset(roomListQueryService);
	}

	@Test
	void 성공_변경은_허용된_식별자만_포함한_INFO_운영_로그를_한번씩_남긴다() throws Exception {
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			when(roomCreateService.createRoom(anyLong(), any(CreateRoomRequest.class))).thenReturn(response());
			when(roomUpdateService.updateRoom(anyLong(), anyLong(), any(RoomUpdateRequest.class)))
				.thenReturn(response());
			when(roomStatusChangeService.cancelRoom(42L, 1L))
				.thenReturn(new RoomStatusResponse(1L, RoomStatus.CANCELED));
			when(roomStatusChangeService.finishRoom(42L, 1L))
				.thenReturn(new RoomStatusResponse(1L, RoomStatus.FINISHED));

			mockMvc.perform(post("/api/rooms").with(csrf()).with(authenticationFor(42L))
				.contentType(MediaType.APPLICATION_JSON).content(validJson())).andExpect(status().isCreated());
			mockMvc.perform(patch("/api/rooms/1").with(csrf()).with(authenticationFor(42L))
				.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"수정 제목\"}"))
				.andExpect(status().isOk());
			mockMvc.perform(delete("/api/rooms/1").with(csrf()).with(authenticationFor(42L)))
				.andExpect(status().isOk());
			mockMvc.perform(patch("/api/rooms/1/status").with(csrf()).with(authenticationFor(42L))
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"FINISHED\"}"))
				.andExpect(status().isOk());

			List<ILoggingEvent> events = appender.list;
			assertEquals(4, events.size());
			assertTrue(events.stream().allMatch(event -> event.getLevel() == Level.INFO));
			assertTrue(events.stream().allMatch(event -> event.getFormattedMessage().contains("roomId=1")));
			assertTrue(events.stream().allMatch(event -> event.getFormattedMessage().contains("actorUserId=42")));
			assertTrue(events.stream().allMatch(event -> event.getFormattedMessage().contains("roomStatus=")));
			assertTrue(events.stream().anyMatch(event -> event.getFormattedMessage().contains("event=room_created")));
			assertTrue(events.stream().anyMatch(event -> event.getFormattedMessage().contains("event=room_updated")));
			assertTrue(events.stream().anyMatch(
				event -> event.getFormattedMessage().contains("event=room_canceled")));
			assertTrue(events.stream().anyMatch(
				event -> event.getFormattedMessage().contains("event=room_finished")));
			assertTrue(events.stream().noneMatch(event -> event.getFormattedMessage().contains("사람 중심")));
			assertTrue(events.stream().noneMatch(event -> event.getFormattedMessage().contains("홍대 장소")));
			assertTrue(events.stream().noneMatch(event -> event.getFormattedMessage().contains("방장")));
		} finally {
			detachLogAppender(appender);
		}
	}

	@Test
	void 필터없는_비로그인_방_목록은_200이고_joinable은_false다() throws Exception {
		when(roomListQueryService.findPage(any(RoomListRequest.class), eq(Optional.empty())))
			.thenReturn(pageResponse(false));

		mockMvc.perform(get("/api/rooms"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.content[0].id").value(1))
			.andExpect(jsonPath("$.data.content[0].joinable").value(false))
			.andExpect(jsonPath("$.data.content[0].waitlistable").value(false))
			.andExpect(jsonPath("$.data.content[0].place").doesNotExist())
			.andExpect(jsonPath("$.data.content[0].host").doesNotExist())
			.andExpect(jsonPath("$.data.content[0].participants").doesNotExist())
			.andExpect(jsonPath("$.data.page").value(0))
			.andExpect(jsonPath("$.data.size").value(10))
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.totalPages").value(1))
			.andExpect(jsonPath("$.data.hasNext").value(false));
	}

	@Test
	void 로그인_방_목록은_현재_사용자를_서비스에_전달한다() throws Exception {
		when(roomListQueryService.findPage(any(RoomListRequest.class), eq(Optional.of(42L))))
			.thenReturn(pageResponse(true));

		mockMvc.perform(
			get("/api/rooms?type=PERSON_FOCUSED&keyword=모임&page=1&size=20")
				.with(authenticationFor(42L)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[0].joinable").value(true))
			.andExpect(jsonPath("$.data.content[0].waitlistable").value(false));

		ArgumentCaptor<RoomListRequest> requestCaptor = ArgumentCaptor.forClass(RoomListRequest.class);
		verify(roomListQueryService).findPage(requestCaptor.capture(), eq(Optional.of(42L)));
		assertEquals(RoomType.PERSON_FOCUSED, requestCaptor.getValue().getType());
		assertEquals("모임", requestCaptor.getValue().getKeyword());
		assertEquals(1, requestCaptor.getValue().getPage());
		assertEquals(20, requestCaptor.getValue().getSize());
	}

	@Test
	void 방_목록의_status는_요청에_그대로_바인딩된다() throws Exception {
		when(roomListQueryService.findPage(any(RoomListRequest.class), eq(Optional.empty())))
			.thenReturn(pageResponse(false));

		mockMvc.perform(get("/api/rooms?status=RECRUITING"))
			.andExpect(status().isOk());

		ArgumentCaptor<RoomListRequest> requestCaptor = ArgumentCaptor.forClass(RoomListRequest.class);
		verify(roomListQueryService).findPage(requestCaptor.capture(), eq(Optional.empty()));
		assertEquals(RoomStatus.RECRUITING, requestCaptor.getValue().getStatus());
	}

	@Test
	void 방_목록의_잘못된_파라미터와_범위는_VALIDATION_ERROR다() throws Exception {
		clearInvocations(roomListQueryService);

		for (String query : List.of(
			"type=INVALID",
			"status=INVALID",
			"gameId=0",
			"gameId=not-a-number",
			"startsAtFrom=not-a-date",
			"startsAtFrom=2099-01-01T00:00:00Z&startsAtTo=2099-01-01T00:00:00Z",
			"minRemainingSeats=0",
			"minRemainingSeats=11",
			"minRemainingSeats=not-a-number",
			"experienceLevels=INVALID",
			"rulemasterOnly=not-a-boolean",
			"rulemasterOnly=yes",
			"rulemasterOnly=on",
			"rulemasterOnly=1",
			"sort=startsAt",
			"page=not-a-number",
			"page=-1",
			"size=0",
			"size=101")) {
			mockMvc.perform(get("/api/rooms" + (query.isEmpty() ? "" : "?" + query)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		}

		verifyNoInteractions(roomListQueryService);
	}

	@Test
	void 방_목록의_뒤집힌_날짜_범위는_VALIDATION_ERROR다() throws Exception {
		clearInvocations(roomListQueryService);

		mockMvc.perform(
			get("/api/rooms?startsAtFrom=2099-01-02T00:00:00Z&startsAtTo=2099-01-01T00:00:00Z"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));

		verifyNoInteractions(roomListQueryService);
	}

	@Test
	void 빈_방_목록_페이지_parameter는_기본값을_유지한다() throws Exception {
		when(roomListQueryService.findPage(any(RoomListRequest.class), eq(Optional.empty())))
			.thenReturn(pageResponse(false));

		mockMvc.perform(get("/api/rooms?page=&size="))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.page").value(0))
			.andExpect(jsonPath("$.data.size").value(10));

		ArgumentCaptor<RoomListRequest> requestCaptor = ArgumentCaptor.forClass(RoomListRequest.class);
		verify(roomListQueryService).findPage(requestCaptor.capture(), eq(Optional.empty()));
		assertEquals(0, requestCaptor.getValue().getPage());
		assertEquals(10, requestCaptor.getValue().getSize());
	}

	@Test
	void P1_방_조건을_중복없이_서비스에_전달한다() throws Exception {
		Instant startsAtFrom = Instant.parse("2099-01-01T00:00:00Z");
		Instant startsAtTo = Instant.parse("2099-01-02T00:00:00Z");
		Set<ExperienceLevel> experienceLevels = Set.of(
			ExperienceLevel.ALL_LEVELS, ExperienceLevel.BEGINNER_WELCOME);
		when(roomListQueryService.findPage(any(RoomListRequest.class), eq(Optional.of(42L))))
			.thenReturn(pageResponse(true));

		mockMvc.perform(
			get("/api/rooms?type=GAME_FOCUSED&gameId=7&keyword=모임"
				+ "&startsAtFrom=2099-01-01T00:00:00Z&startsAtTo=2099-01-02T00:00:00Z"
				+ "&minRemainingSeats=2&experienceLevels=ALL_LEVELS"
				+ "&experienceLevels=ALL_LEVELS&experienceLevels=BEGINNER_WELCOME&rulemasterOnly=true")
				.with(authenticationFor(42L)))
			.andExpect(status().isOk());

		ArgumentCaptor<RoomListRequest> requestCaptor = ArgumentCaptor.forClass(RoomListRequest.class);
		verify(roomListQueryService).findPage(requestCaptor.capture(), eq(Optional.of(42L)));
		RoomListRequest request = requestCaptor.getValue();
		assertEquals(RoomType.GAME_FOCUSED, request.getType());
		assertEquals(7L, request.getGameId());
		assertEquals("모임", request.getKeyword());
		assertEquals(startsAtFrom, request.getStartsAtFrom());
		assertEquals(startsAtTo, request.getStartsAtTo());
		assertEquals(2, request.getMinRemainingSeats());
		assertEquals(experienceLevels, request.getExperienceLevels());
		assertTrue(request.isRulemasterOnly());
	}

	@Autowired
	private RoomUpdateService roomUpdateService;
	@Autowired
	private RoomStatusChangeService roomStatusChangeService;

	@Test
	void 인증없는_방_생성은_UNAUTHENTICATED다() throws Exception {
		clearInvocations(roomCreateService);
		mockMvc.perform(post("/api/rooms").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.status").value(401))
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
		verifyNoInteractions(roomCreateService);
	}

	@Test
	void 인증과_CSRF가_있는_유효한_요청은_201_응답_봉투를_반환한다() throws Exception {
		ParticipantRoomResponse response = response();
		when(roomCreateService.createRoom(anyLong(), any(CreateRoomRequest.class)))
			.thenReturn(response);
		mockMvc.perform(
			post("/api/rooms")
				.with(csrf())
				.with(authenticationFor(42L))
				.contentType(MediaType.APPLICATION_JSON)
				.content(validJson()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value(201))
			.andExpect(jsonPath("$.code").doesNotExist())
			.andExpect(jsonPath("$.data.id").value(1))
			.andExpect(jsonPath("$.data.roomType").value("PERSON_FOCUSED"))
			.andExpect(jsonPath("$.data.title").value("사람 중심"))
			.andExpect(jsonPath("$.data.description").value(Matchers.nullValue()))
			.andExpect(jsonPath("$.data.game").value(Matchers.nullValue()))
			.andExpect(jsonPath("$.data.experienceLevel").value("ALL_LEVELS"))
			.andExpect(jsonPath("$.data.isRulemasterLed").value(false))
			.andExpect(jsonPath("$.data.startsAt").value("2099-01-01T19:00:00+09:00"))
			.andExpect(jsonPath("$.data.region").value("홍대"))
			.andExpect(jsonPath("$.data.recruitmentCapacity").value(3))
			.andExpect(jsonPath("$.data.participantCount").value(1))
			.andExpect(jsonPath("$.data.remainingRecruitmentSeats").value(3))
			.andExpect(jsonPath("$.data.status").value("RECRUITING"))
			.andExpect(jsonPath("$.data.joinable").value(false))
			.andExpect(jsonPath("$.data.waitlistable").value(false))
			.andExpect(jsonPath("$.data.myRole").value("HOST"))
			.andExpect(jsonPath("$.data.place").value("홍대 장소"))
			.andExpect(jsonPath("$.data.host.nickname").value("방장"))
			.andExpect(jsonPath("$.data.participants").isArray())
			.andExpect(jsonPath("$.data.participants").value(Matchers.hasSize(1)))
			.andExpect(jsonPath("$.data.participants[0].nickname").value("방장"));
	}

	@Test
	void 인증과_CSRF가_있어도_요청값_검증은_VALIDATION_ERROR다() throws Exception {
		mockMvc.perform(
			post("/api/rooms")
				.with(csrf())
				.with(authenticationFor(42L))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"roomType\":\"PERSON_FOCUSED\",\"title\":\"   \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
	}

	@Test
	void 인증만_있는_유효한_요청은_CSRF_TOKEN_INVALID이고_Service를_호출하지_않는다() throws Exception {
		clearInvocations(roomCreateService);

		mockMvc.perform(
			post("/api/rooms")
				.with(authenticationFor(42L))
				.contentType(MediaType.APPLICATION_JSON)
				.content(validJson()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));

		verifyNoInteractions(roomCreateService);
	}

	@Test
	void 인증과_CSRF가_있는_부분_수정은_200_응답을_반환하고_명시적_null을_보존한다() throws Exception {
		clearInvocations(roomUpdateService);
		when(roomUpdateService.updateRoom(anyLong(), anyLong(), any(RoomUpdateRequest.class)))
			.thenReturn(response());

		mockMvc.perform(
			patch("/api/rooms/1")
				.with(csrf())
				.with(authenticationFor(42L))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"수정 제목\",\"description\":null}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.joinable").value(false))
			.andExpect(jsonPath("$.data.waitlistable").value(false))
			.andExpect(jsonPath("$.data.myRole").value("HOST"));

		ArgumentCaptor<RoomUpdateRequest> requestCaptor = ArgumentCaptor.forClass(RoomUpdateRequest.class);
		verify(roomUpdateService).updateRoom(anyLong(), anyLong(), requestCaptor.capture());
		org.junit.jupiter.api.Assertions.assertTrue(requestCaptor.getValue().hasTitle());
		org.junit.jupiter.api.Assertions.assertTrue(requestCaptor.getValue().hasDescription());
		org.junit.jupiter.api.Assertions.assertEquals(null, requestCaptor.getValue().description());
		org.junit.jupiter.api.Assertions.assertFalse(requestCaptor.getValue().hasGameId());
	}

	@Test
	void gameId를_명시적_null로_보내면_선택_해제_요청으로_전달한다() throws Exception {
		clearInvocations(roomUpdateService);
		when(roomUpdateService.updateRoom(anyLong(), anyLong(), any(RoomUpdateRequest.class)))
			.thenReturn(response());

		mockMvc.perform(
			patch("/api/rooms/1")
				.with(csrf())
				.with(authenticationFor(42L))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"gameId\":null}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200));

		ArgumentCaptor<RoomUpdateRequest> requestCaptor = ArgumentCaptor.forClass(RoomUpdateRequest.class);
		verify(roomUpdateService).updateRoom(anyLong(), anyLong(), requestCaptor.capture());
		RoomUpdateRequest request = requestCaptor.getValue();
		org.junit.jupiter.api.Assertions.assertTrue(request.hasGameId());
		org.junit.jupiter.api.Assertions.assertEquals(null, request.gameId());
	}

	@Test
	void 모든_허용_필드를_제공하면_수정_요청에_그대로_전달한다() throws Exception {
		clearInvocations(roomUpdateService);
		when(roomUpdateService.updateRoom(anyLong(), anyLong(), any(RoomUpdateRequest.class)))
			.thenReturn(response());

		mockMvc.perform(
			patch("/api/rooms/1")
				.with(csrf())
				.with(authenticationFor(42L))
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					"""
						{
						  "title": "  새 제목  ",
						  "place": "  새 장소  ",
						  "description": "새 설명",
						  "gameId": 7,
						  "experienceLevel": "BEGINNER_WELCOME",
						  "isRulemasterLed": true,
						  "startsAt": "2099-01-02T19:00:00+09:00",
						  "recruitmentCapacity": 4
						}
						"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200));

		ArgumentCaptor<RoomUpdateRequest> requestCaptor = ArgumentCaptor.forClass(RoomUpdateRequest.class);
		verify(roomUpdateService).updateRoom(anyLong(), anyLong(), requestCaptor.capture());
		RoomUpdateRequest request = requestCaptor.getValue();
		org.junit.jupiter.api.Assertions.assertEquals("새 제목", request.title());
		org.junit.jupiter.api.Assertions.assertEquals("새 장소", request.place());
		org.junit.jupiter.api.Assertions.assertEquals("새 설명", request.description());
		org.junit.jupiter.api.Assertions.assertEquals(7L, request.gameId());
		org.junit.jupiter.api.Assertions.assertEquals(
			ExperienceLevel.BEGINNER_WELCOME, request.experienceLevel());
		org.junit.jupiter.api.Assertions.assertTrue(request.rulemasterLed());
		org.junit.jupiter.api.Assertions.assertEquals(
			Instant.parse("2099-01-02T10:00:00Z"), request.startsAt());
		org.junit.jupiter.api.Assertions.assertEquals(4, request.recruitmentCapacity());
	}

	@Test
	void 수정_불가_필드를_포함하면_VALIDATION_ERROR이고_Service를_호출하지_않는다() throws Exception {
		clearInvocations(roomUpdateService);

		mockMvc.perform(
			patch("/api/rooms/1")
				.with(csrf())
				.with(authenticationFor(42L))
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					"{\"roomType\":\"GAME_FOCUSED\",\"region\":\"홍대\",\"status\":\"CLOSED\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));

		verifyNoInteractions(roomUpdateService);
	}

	@Test
	void 수정_가능하지만_null을_허용하지_않는_필드는_VALIDATION_ERROR다() throws Exception {
		clearInvocations(roomUpdateService);

		mockMvc.perform(
			patch("/api/rooms/1")
				.with(csrf())
				.with(authenticationFor(42L))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":null}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));

		verifyNoInteractions(roomUpdateService);
	}

	@Test
	void 인증없는_취소와_종료는_UNAUTHENTICATED이고_Service를_호출하지_않는다() throws Exception {
		clearInvocations(roomStatusChangeService);

		mockMvc.perform(delete("/api/rooms/1"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
		mockMvc.perform(
			patch("/api/rooms/1/status")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"FINISHED\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));

		verifyNoInteractions(roomStatusChangeService);
	}

	@Test
	void 인증만_있는_취소와_종료는_CSRF_TOKEN_INVALID이고_Service를_호출하지_않는다() throws Exception {
		clearInvocations(roomStatusChangeService);

		mockMvc.perform(delete("/api/rooms/1").with(authenticationFor(42L)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));
		mockMvc.perform(
			patch("/api/rooms/1/status")
				.with(authenticationFor(42L))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"FINISHED\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));

		verifyNoInteractions(roomStatusChangeService);
	}

	@Test
	void 종료_하위_경로는_PATCH만_허용하고_Service를_호출하지_않는다() throws Exception {
		clearInvocations(roomStatusChangeService);

		mockMvc.perform(
			post("/api/rooms/1/status")
				.with(csrf())
				.with(authenticationFor(42L))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"FINISHED\"}"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(jsonPath("$.code").value(ErrorCode.METHOD_NOT_ALLOWED.getCode()));

		verifyNoInteractions(roomStatusChangeService);
	}

	@Test
	void 인증과_CSRF가_있는_취소는_CANCELED_상태_응답을_반환한다() throws Exception {
		clearInvocations(roomStatusChangeService);
		when(roomStatusChangeService.cancelRoom(42L, 1L))
			.thenReturn(new RoomStatusResponse(1L, RoomStatus.CANCELED));

		mockMvc.perform(delete("/api/rooms/1").with(csrf()).with(authenticationFor(42L)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.roomId").value(1))
			.andExpect(jsonPath("$.data.roomStatus").value("CANCELED"));

		verify(roomStatusChangeService).cancelRoom(42L, 1L);
	}

	@Test
	void 인증과_CSRF가_있는_FINISHED_종료_요청은_상태_응답을_반환한다() throws Exception {
		clearInvocations(roomStatusChangeService);
		when(roomStatusChangeService.finishRoom(42L, 1L))
			.thenReturn(new RoomStatusResponse(1L, RoomStatus.FINISHED));

		mockMvc.perform(
			patch("/api/rooms/1/status")
				.with(csrf())
				.with(authenticationFor(42L))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"FINISHED\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.roomStatus").value("FINISHED"));

		verify(roomStatusChangeService).finishRoom(42L, 1L);
	}

	@Test
	void 종료_요청의_누락_null_FINISHED_외_상태와_잘못된_ID는_VALIDATION_ERROR다() throws Exception {
		clearInvocations(roomStatusChangeService);

		for (String body : List.of("{}", "{\"status\":null}", "{\"status\":\"CLOSED\"}")) {
			mockMvc.perform(
				patch("/api/rooms/1/status")
					.with(csrf())
					.with(authenticationFor(42L))
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		}
		mockMvc.perform(
			patch("/api/rooms/0/status")
				.with(csrf())
				.with(authenticationFor(42L))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"FINISHED\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));

		verifyNoInteractions(roomStatusChangeService);
	}

	private RequestPostProcessor authenticationFor(long userId) {
		return authentication(
			new UsernamePasswordAuthenticationToken(
				new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
	}

	private ParticipantRoomResponse response() {
		NicknameSummary host = new NicknameSummary("방장");
		return new ParticipantRoomResponse(
			1L,
			RoomType.PERSON_FOCUSED,
			"사람 중심",
			null,
			null,
			ExperienceLevel.ALL_LEVELS,
			false,
			Instant.parse("2099-01-01T10:00:00Z"),
			"홍대",
			3,
			1,
			3,
			RoomStatus.RECRUITING,
			false,
			false,
			MyRole.HOST,
			"홍대 장소",
			host,
			List.of(host));
	}

	private PageResponse<PublicRoomResponse> pageResponse(boolean joinable) {
		return new PageResponse<>(
			List.of(
				new PublicRoomResponse(
					1L,
					RoomType.GAME_FOCUSED,
					"게임 모임",
					null,
					new cloud.bamsongi.albammate.game.contract.GameSummary(
						7L, 1007L, "카탄"),
					ExperienceLevel.ALL_LEVELS,
					false,
					Instant.parse("2099-01-01T10:00:00Z"),
					"홍대",
					3,
					2,
					2,
					RoomStatus.RECRUITING,
					joinable,
					false)),
			0,
			10,
			1,
			1,
			false);
	}

	private String validJson() {
		return """
			{
			  "roomType": "PERSON_FOCUSED",
			  "title": "사람 중심",
			  "description": null,
			  "gameId": null,
			  "experienceLevel": "ALL_LEVELS",
			  "isRulemasterLed": false,
			  "startsAt": "2099-01-01T19:00:00+09:00",
			  "place": "홍대 장소",
			  "recruitmentCapacity": 3
			}
			""";
	}

	private ListAppender<ILoggingEvent> attachLogAppender() {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(RoomController.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(RoomController.class);
		logger.detachAppender(appender);
		appender.stop();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestBeans {

		@Bean
		RoomCreateService roomCreateService() {
			return Mockito.mock(RoomCreateService.class);
		}

		@Bean
		RoomListQueryService roomListQueryService() {
			return Mockito.mock(RoomListQueryService.class);
		}

		@Bean
		RoomDetailService roomDetailService() {
			return Mockito.mock(RoomDetailService.class);
		}

		@Bean
		RoomUpdateService roomUpdateService() {
			return Mockito.mock(RoomUpdateService.class);
		}

		@Bean
		RoomStatusChangeService roomStatusChangeService() {
			return Mockito.mock(RoomStatusChangeService.class);
		}
	}
}
