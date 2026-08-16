package cloud.bamsongi.albammate.global.security.error;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/** Spring Security 필터에서 MVC 예외 처리기와 같은 JSON 오류 봉투를 작성한다. */
@RequiredArgsConstructor
@Component
@Slf4j
public final class SecurityErrorResponseWriter {

	@NonNull private final ObjectMapper objectMapper;

	public void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
		write(null, response, errorCode);
	}

	public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode errorCode)
		throws IOException {
		if (response.isCommitted()) {
			return;
		}
		logGamePlayedStateFailure(request, errorCode);
		response.setStatus(errorCode.getStatus());
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(errorCode));
	}

	private void logGamePlayedStateFailure(HttpServletRequest request, ErrorCode errorCode) {
		if (request == null || !("PUT".equals(request.getMethod()) || "DELETE".equals(request.getMethod()))) {
			return;
		}
		String gameId = gameIdPathSegment(request.getRequestURI());
		if (gameId == null) {
			return;
		}
		String action = "PUT".equals(request.getMethod()) ? "mark" : "unmark";
		Long accessRestrictedGameId = positiveGameIdOrNull(gameId);
		if (accessRestrictedGameId == null) {
			log.info(
				"event=game_played_state_change_failed action={} outcome=rejected failureCode={}",
				action,
				errorCode.getCode());
			return;
		}
		log.info(
			"event=game_played_state_change_failed action={} outcome=rejected failureCode={} gameId={}",
			action,
			errorCode.getCode(),
			accessRestrictedGameId);
	}

	private String gameIdPathSegment(String requestUri) {
		String prefix = "/api/users/me/played-games/";
		if (!requestUri.startsWith(prefix)) {
			return null;
		}
		String gameId = requestUri.substring(prefix.length());
		return gameId.isEmpty() || gameId.contains("/") ? null : gameId;
	}

	private Long positiveGameIdOrNull(String gameId) {
		try {
			long value = Long.parseLong(gameId);
			return value > 0 ? value : null;
		} catch (NumberFormatException exception) {
			return null;
		}
	}
}
