package cloud.bamsongi.albammate.notification.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;

import cloud.bamsongi.albammate.global.config.SecurityConfig;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;
import cloud.bamsongi.albammate.global.response.PageResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.global.security.currentuser.SecurityContextCurrentUserAccessor;
import cloud.bamsongi.albammate.global.security.error.ApiAccessDeniedHandler;
import cloud.bamsongi.albammate.global.security.error.ApiAuthenticationEntryPoint;
import cloud.bamsongi.albammate.global.security.error.SecurityErrorResponseWriter;
import cloud.bamsongi.albammate.notification.dto.NotificationListItem;
import cloud.bamsongi.albammate.notification.dto.UnreadNotificationCountResponse;
import cloud.bamsongi.albammate.notification.enums.NotificationType;
import cloud.bamsongi.albammate.notification.service.command.NotificationReadCommandService;
import cloud.bamsongi.albammate.notification.service.query.NotificationListQueryService;
import cloud.bamsongi.albammate.notification.service.query.UnreadNotificationCountQueryService;

@WebMvcTest(NotificationController.class)
@Import({
	SecurityConfig.class,
	ApiAccessDeniedHandler.class,
	ApiAuthenticationEntryPoint.class,
	SecurityErrorResponseWriter.class,
	GlobalExceptionHandler.class,
	SecurityContextCurrentUserAccessor.class,
	NotificationControllerTest.TestBeans.class
})
class NotificationControllerTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private NotificationListQueryService notificationListQueryService;
	@Autowired
	private UnreadNotificationCountQueryService unreadNotificationCountQueryService;
	@Autowired
	private NotificationReadCommandService notificationReadCommandService;

	@Test
	void 비로그인_두_GET은_UNAUTHENTICATED다() throws Exception {
		clearInvocations(notificationListQueryService, unreadNotificationCountQueryService);
		mockMvc.perform(get("/api/users/me/notifications"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
		mockMvc.perform(get("/api/users/me/notifications/unread-count"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
		verifyNoInteractions(notificationListQueryService, unreadNotificationCountQueryService);
	}

	@Test
	void 비로그인_두_PATCH는_UNAUTHENTICATED이고_Service를_호출하지_않는다() throws Exception {
		clearInvocations(notificationReadCommandService);
		for (String path : List.of("/api/users/me/notifications/1", "/api/users/me/notifications")) {
			mockMvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
		}
		verifyNoInteractions(notificationReadCommandService);
	}

	@Test
	void 목록은_기본_페이지와_허용된_필드만_봉투에_반환한다() throws Exception {
		when(notificationListQueryService.findPage(42L, 0, 10)).thenReturn(page());
		mockMvc.perform(get("/api/users/me/notifications").with(authenticationFor(42L)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.page").value(0))
			.andExpect(jsonPath("$.data.content[0].id").value(1))
			.andExpect(jsonPath("$.data.content[0].type").value("PARTICIPANT_JOINED"))
			.andExpect(jsonPath("$.data.content[0].roomId").value(3))
			.andExpect(jsonPath("$.data.content[0].roomTitle").value("현재 방 제목"))
			.andExpect(jsonPath("$.data.content[0].readAt").value(nullValue()))
			.andExpect(jsonPath("$.data.content[0].createdAt").exists());
		for (String field : List.of("message", "expiresAt", "recordedAt", "recipientUserId", "sourceEventId",
			"participant", "participants", "place", "auth", "password", "sessionId")) {
			mockMvc.perform(get("/api/users/me/notifications").with(authenticationFor(42L)))
				.andExpect(jsonPath("$.data.content[0]." + field).doesNotExist());
		}
		verify(notificationListQueryService, atLeastOnce()).findPage(42L, 0, 10);
	}

	@Test
	void page_size_경계와_미확인_응답을_검증한다() throws Exception {
		when(notificationListQueryService.findPage(42L, 0, 1)).thenReturn(page());
		when(notificationListQueryService.findPage(42L, 0, 100)).thenReturn(page());
		when(notificationListQueryService.findPage(42L, 3, 100)).thenReturn(page());
		when(unreadNotificationCountQueryService.countUnread(42L)).thenReturn(new UnreadNotificationCountResponse(0));
		mockMvc.perform(get("/api/users/me/notifications?page=3&size=100").with(authenticationFor(42L)))
			.andExpect(status().isOk());
		mockMvc.perform(get("/api/users/me/notifications?page=0&size=1").with(authenticationFor(42L)))
			.andExpect(status().isOk());
		mockMvc.perform(get("/api/users/me/notifications?page=0&size=100").with(authenticationFor(42L)))
			.andExpect(status().isOk());
		mockMvc.perform(get("/api/users/me/notifications/unread-count").with(authenticationFor(42L)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.unreadCount").value(0));
		for (String query : List.of("page=-1", "size=0", "size=101", "page=x", "size=x", "sort=id")) {
			mockMvc.perform(get("/api/users/me/notifications?" + query).with(authenticationFor(42L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		}
		verify(notificationListQueryService).findPage(42L, 3, 100);
		verify(unreadNotificationCountQueryService).countUnread(42L);
	}

	@Test
	void 단건과_일괄_PATCH는_인증과_CSRF_뒤에_각각의_Service_응답을_반환한다() throws Exception {
		NotificationListItem readItem = new NotificationListItem(1L, NotificationType.PARTICIPANT_JOINED, 3L,
			"현재 방 제목", Instant.parse("2026-08-03T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"));
		when(notificationReadCommandService.readOne(42L, 1L)).thenReturn(readItem);
		when(notificationReadCommandService.readAll(42L)).thenReturn(
			new cloud.bamsongi.albammate.notification.dto.NotificationBulkReadResponse(
				2, 4L, Instant.parse("2026-08-03T00:00:00Z")));

		mockMvc.perform(patch("/api/users/me/notifications/1")
			.with(authenticationFor(42L))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"read\":true}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.id").value(1))
			.andExpect(jsonPath("$.data.readAt").exists());
		mockMvc.perform(patch("/api/users/me/notifications")
			.with(authenticationFor(42L))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"read\":true}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.updatedCount").value(2))
			.andExpect(jsonPath("$.data.boundaryNotificationId").value(4))
			.andExpect(jsonPath("$.data.readAt").exists());
		verify(notificationReadCommandService).readOne(42L, 1L);
		verify(notificationReadCommandService).readAll(42L);
	}

	@Test
	void 단건과_일괄_PATCH는_잘못된_입력이나_CSRF에서_상태를_변경하지_않는다() throws Exception {
		clearInvocations(notificationReadCommandService);
		for (String body : List.of("null", "{}", "{\"read\":false}",
			"{\"read\":true,\"readAt\":\"2026-08-03T00:00:00Z\"}", "{\"read\":true,\"other\":1}")) {
			mockMvc.perform(patch("/api/users/me/notifications/1")
				.with(authenticationFor(42L))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		}
		mockMvc.perform(patch("/api/users/me/notifications/0")
			.with(authenticationFor(42L))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"read\":true}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		mockMvc.perform(patch("/api/users/me/notifications/1")
			.with(authenticationFor(42L))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"read\":true}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));
		verifyNoInteractions(notificationReadCommandService);
	}

	private PageResponse<NotificationListItem> page() {
		return new PageResponse<>(List.of(new NotificationListItem(1L, NotificationType.PARTICIPANT_JOINED, 3L,
			"현재 방 제목", null, Instant.parse("2026-08-01T00:00:00Z"))), 0, 10, 1, 1, false);
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor authenticationFor(long userId) {
		return authentication(new UsernamePasswordAuthenticationToken(new CurrentUserPrincipal(userId), null,
			AuthorityUtils.NO_AUTHORITIES));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestBeans {
		@Bean
		NotificationListQueryService notificationListQueryService() {
			return mock(NotificationListQueryService.class);
		}

		@Bean
		UnreadNotificationCountQueryService unreadNotificationCountQueryService() {
			return mock(UnreadNotificationCountQueryService.class);
		}

		@Bean
		NotificationReadCommandService notificationReadCommandService() {
			return mock(NotificationReadCommandService.class);
		}
	}
}
