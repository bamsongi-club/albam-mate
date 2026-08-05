package cloud.bamsongi.albammate.room.controller;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.global.config.SecurityConfig;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.global.security.currentuser.SecurityContextCurrentUserAccessor;
import cloud.bamsongi.albammate.global.security.error.ApiAccessDeniedHandler;
import cloud.bamsongi.albammate.global.security.error.ApiAuthenticationEntryPoint;
import cloud.bamsongi.albammate.global.security.error.SecurityErrorResponseWriter;
import cloud.bamsongi.albammate.room.dto.NicknameSummary;
import cloud.bamsongi.albammate.room.dto.ParticipantRoomResponse;
import cloud.bamsongi.albammate.room.dto.PublicRoomResponse;
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
	RoomControllerDetailTest.TestBeans.class
})
class RoomControllerDetailTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private RoomDetailService roomDetailService;

	@Test
	void 익명_GET은_CSRF_없이_공개_응답과_캐시방지_헤더를_반환한다() throws Exception {
		when(roomDetailService.findRoomDetail(1L, Optional.empty()))
			.thenReturn(publicResponse(false));

		mockMvc.perform(get("/api/rooms/1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.id").value(1))
			.andExpect(jsonPath("$.data.joinable").value(false))
			.andExpect(jsonPath("$.data.waitlistable").value(false))
			.andExpect(jsonPath("$.data.place").doesNotExist())
			.andExpect(jsonPath("$.data.host").doesNotExist())
			.andExpect(jsonPath("$.data.participants").doesNotExist())
			.andExpect(jsonPath("$.data.myRole").doesNotExist())
			.andExpect(jsonPath("$.data.hostUserId").doesNotExist())
			.andExpect(header().string("Cache-Control", "private, no-store"))
			.andExpect(header().string("Vary", "Cookie"));

		verify(roomDetailService).findRoomDetail(1L, Optional.empty());
	}

	@Test
	void 관계없는_로그인_사용자는_공개_응답을_받는다() throws Exception {
		when(roomDetailService.findRoomDetail(1L, Optional.of(99L)))
			.thenReturn(publicResponse(true));

		mockMvc.perform(get("/api/rooms/1").with(authenticationFor(99L)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.joinable").value(true))
			.andExpect(jsonPath("$.data.waitlistable").value(false))
			.andExpect(jsonPath("$.data.place").doesNotExist())
			.andExpect(jsonPath("$.data.participants").doesNotExist())
			.andExpect(header().string("Cache-Control", "private, no-store"));
	}

	@Test
	void 대기_신청_가능_상세는_waitlistable_true를_직렬화한다() throws Exception {
		when(roomDetailService.findRoomDetail(1L, Optional.of(99L)))
			.thenReturn(publicResponse(false, true));

		mockMvc.perform(get("/api/rooms/1").with(authenticationFor(99L)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.joinable").value(false))
			.andExpect(jsonPath("$.data.waitlistable").value(true));
	}

	@Test
	void 주최자는_HOST_역할과_관계자_필드를_받는다() throws Exception {
		when(roomDetailService.findRoomDetail(1L, Optional.of(42L)))
			.thenReturn(participantResponse(MyRole.HOST));

		mockMvc.perform(get("/api/rooms/1").with(authenticationFor(42L)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.myRole").value("HOST"))
			.andExpect(jsonPath("$.data.place").value("정확한 장소"))
			.andExpect(jsonPath("$.data.host.nickname").value("방장"))
			.andExpect(jsonPath("$.data.participants[0].nickname").value("방장"))
			.andExpect(jsonPath("$.data.participants[1].nickname").value("참가자"))
			.andExpect(jsonPath("$.data.participants[0].id").doesNotExist())
			.andExpect(header().string("Vary", "Cookie"));
	}

	@Test
	void ACTIVE_참가자는_JOINED_역할을_받는다() throws Exception {
		when(roomDetailService.findRoomDetail(1L, Optional.of(77L)))
			.thenReturn(participantResponse(MyRole.JOINED));

		mockMvc.perform(get("/api/rooms/1").with(authenticationFor(77L)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.myRole").value("JOINED"))
			.andExpect(jsonPath("$.data.place").value("정확한 장소"));
	}

	@Test
	void CANCELED_참가자는_관계자_필드_없이_다시_참가_가능한_공개_응답을_받는다() throws Exception {
		when(roomDetailService.findRoomDetail(1L, Optional.of(88L)))
			.thenReturn(publicResponse(true));

		mockMvc.perform(get("/api/rooms/1").with(authenticationFor(88L)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.joinable").value(true))
			.andExpect(jsonPath("$.data.waitlistable").value(false))
			.andExpect(jsonPath("$.data.myRole").doesNotExist())
			.andExpect(jsonPath("$.data.place").doesNotExist());
	}

	@Test
	void 최종_상태_비관계자와_없는_방은_같은_ROOM_NOT_FOUND다() throws Exception {
		when(roomDetailService.findRoomDetail(1L, Optional.empty()))
			.thenThrow(new BusinessException(ErrorCode.ROOM_NOT_FOUND));
		when(roomDetailService.findRoomDetail(404L, Optional.empty()))
			.thenThrow(new BusinessException(ErrorCode.ROOM_NOT_FOUND));

		mockMvc.perform(get("/api/rooms/1"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
		mockMvc.perform(get("/api/rooms/404"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
	}

	@Test
	void 최소값_미만_방_ID는_VALIDATION_ERROR이고_서비스를_호출하지_않는다() throws Exception {
		clearInvocations(roomDetailService);

		mockMvc.perform(get("/api/rooms/0"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mockMvc.perform(get("/api/rooms/not-a-number"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(roomDetailService);
	}

	private RequestPostProcessor authenticationFor(long userId) {
		return authentication(
			new UsernamePasswordAuthenticationToken(
				new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
	}

	private PublicRoomResponse publicResponse(boolean joinable) {
		return publicResponse(joinable, false);
	}

	private PublicRoomResponse publicResponse(boolean joinable, boolean waitlistable) {
		return new PublicRoomResponse(
			1L,
			RoomType.GAME_FOCUSED,
			"공개 방",
			"소개",
			new GameSummary(7L, 1007L, "카탄"),
			ExperienceLevel.ALL_LEVELS,
			false,
			Instant.parse("2099-01-01T10:00:00Z"),
			"홍대",
			3,
			2,
			2,
			RoomStatus.RECRUITING,
			joinable,
			waitlistable);
	}

	private ParticipantRoomResponse participantResponse(MyRole myRole) {
		NicknameSummary host = new NicknameSummary("방장");
		return new ParticipantRoomResponse(
			1L,
			RoomType.PERSON_FOCUSED,
			"관계자 방",
			null,
			null,
			ExperienceLevel.ALL_LEVELS,
			false,
			Instant.parse("2099-01-01T10:00:00Z"),
			"홍대",
			3,
			2,
			2,
			RoomStatus.RECRUITING,
			false,
			false,
			myRole,
			"정확한 장소",
			host,
			List.of(host, new NicknameSummary("참가자")));
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
