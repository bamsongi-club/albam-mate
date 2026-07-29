package cloud.bamsongi.albammate.room.controller;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

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
import cloud.bamsongi.albammate.global.response.PageResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.global.security.currentuser.SecurityContextCurrentUserAccessor;
import cloud.bamsongi.albammate.global.security.error.ApiAccessDeniedHandler;
import cloud.bamsongi.albammate.global.security.error.ApiAuthenticationEntryPoint;
import cloud.bamsongi.albammate.global.security.error.SecurityErrorResponseWriter;
import cloud.bamsongi.albammate.room.dto.MyRoomListItem;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.MyRole;
import cloud.bamsongi.albammate.room.enums.MyRoomRole;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.service.MyRoomQueryService;

@WebMvcTest(controllers = MyRoomController.class)
@Import({
	SecurityConfig.class,
	ApiAccessDeniedHandler.class,
	ApiAuthenticationEntryPoint.class,
	SecurityErrorResponseWriter.class,
	GlobalExceptionHandler.class,
	SecurityContextCurrentUserAccessor.class,
	MyRoomControllerTest.TestBeans.class
})
class MyRoomControllerTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private MyRoomQueryService myRoomQueryService;

	@Test
	void 인증없는_내_모임_조회는_UNAUTHENTICATED다() throws Exception {
		clearInvocations(myRoomQueryService);

		mockMvc.perform(get("/api/users/me/rooms?role=all"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));

		verifyNoInteractions(myRoomQueryService);
	}

	@Test
	void 인증된_조회는_응답_봉투와_비식별_필드만_반환한다() throws Exception {
		when(myRoomQueryService.findPage(42L, MyRoomRole.all, 0, 10)).thenReturn(response());

		mockMvc.perform(get("/api/users/me/rooms?role=all").with(authenticationFor(42L)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.content[0].myRole").value("JOINED"))
			.andExpect(jsonPath("$.data.content[0].participationStatus").value("ACTIVE"))
			.andExpect(jsonPath("$.data.content[0].joinable").value(false))
			.andExpect(jsonPath("$.data.content[0].place").doesNotExist())
			.andExpect(jsonPath("$.data.content[0].host").doesNotExist())
			.andExpect(jsonPath("$.data.content[0].participants").doesNotExist())
			.andExpect(jsonPath("$.data.content[0].userId").doesNotExist());

		verify(myRoomQueryService).findPage(42L, MyRoomRole.all, 0, 10);
	}

	@Test
	void 누락_대문자_역할과_잘못된_페이지는_VALIDATION_ERROR다() throws Exception {
		clearInvocations(myRoomQueryService);

		for (String query : List.of(
			"",
			"role=ALL",
			"role=all&page=not-a-number",
			"role=all&page=-1",
			"role=all&size=0",
			"role=all&size=101",
			"role=all&sort=id")) {
			mockMvc.perform(
				get("/api/users/me/rooms" + (query.isEmpty() ? "" : "?" + query))
					.with(authenticationFor(42L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		}

		verifyNoInteractions(myRoomQueryService);
	}

	@Test
	void 빈_내_모임_페이지_parameter는_기본값을_유지한다() throws Exception {
		when(myRoomQueryService.findPage(42L, MyRoomRole.all, 0, 10)).thenReturn(response());

		mockMvc.perform(get("/api/users/me/rooms?role=all&page=&size=").with(authenticationFor(42L)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.page").value(0))
			.andExpect(jsonPath("$.data.size").value(10));

		verify(myRoomQueryService).findPage(42L, MyRoomRole.all, 0, 10);
	}

	@Test
	void 상태_보정_충돌은_ROOM_CONCURRENT_MODIFICATION으로_반환한다() throws Exception {
		when(myRoomQueryService.findPage(42L, MyRoomRole.joined, 0, 10))
			.thenThrow(new BusinessException(ErrorCode.ROOM_CONCURRENT_MODIFICATION));

		mockMvc.perform(get("/api/users/me/rooms?role=joined").with(authenticationFor(42L)))
			.andExpect(status().isConflict())
			.andExpect(
				jsonPath("$.code").value(ErrorCode.ROOM_CONCURRENT_MODIFICATION.getCode()));
	}

	private RequestPostProcessor authenticationFor(long userId) {
		return authentication(
			new UsernamePasswordAuthenticationToken(
				new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
	}

	private PageResponse<MyRoomListItem> response() {
		return new PageResponse<>(
			List.of(
				new MyRoomListItem(
					1L,
					RoomType.GAME_FOCUSED,
					"게임 모임",
					null,
					new GameSummary(7L, 1007L, "카탄"),
					ExperienceLevel.ALL_LEVELS,
					false,
					Instant.parse("2099-01-01T10:00:00Z"),
					"홍대",
					3,
					2,
					2,
					RoomStatus.RECRUITING,
					false,
					MyRole.JOINED,
					ParticipationStatus.ACTIVE)),
			0,
			10,
			1,
			1,
			false);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestBeans {

		@Bean
		MyRoomQueryService myRoomQueryService() {
			return Mockito.mock(MyRoomQueryService.class);
		}
	}
}
