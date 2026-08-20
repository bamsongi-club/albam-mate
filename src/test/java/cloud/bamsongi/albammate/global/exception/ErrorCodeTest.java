package cloud.bamsongi.albammate.global.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ErrorCodeTest {

	@Test
	void API_오류_코드_카탈로그가_HTTP_상태와_기본_메시지를_함께_제공한다() {
		Map<ErrorCode, String> expectedMessages = Map.ofEntries(
			Map.entry(ErrorCode.VALIDATION_ERROR, "요청값 검증에 실패했습니다."),
			Map.entry(ErrorCode.METHOD_NOT_ALLOWED, "허용되지 않은 HTTP 메서드입니다."),
			Map.entry(ErrorCode.NOT_ACCEPTABLE, "요청한 응답 미디어 타입을 제공할 수 없습니다."),
			Map.entry(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 미디어 타입입니다."),
			Map.entry(ErrorCode.RESOURCE_NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
			Map.entry(ErrorCode.UNAUTHENTICATED, "인증이 필요합니다."),
			Map.entry(ErrorCode.FORBIDDEN, "요청을 수행할 권한이 없습니다."),
			Map.entry(ErrorCode.CSRF_TOKEN_INVALID, "CSRF 토큰이 없거나 유효하지 않습니다."),
			Map.entry(ErrorCode.INVALID_CREDENTIALS, "이메일 또는 비밀번호가 일치하지 않습니다."),
			Map.entry(ErrorCode.EMAIL_ALREADY_EXISTS, "이미 사용 중인 이메일입니다."),
			Map.entry(ErrorCode.SOCIAL_PROVIDER_NOT_AVAILABLE, "사용할 수 없는 소셜 로그인 제공자입니다."),
			Map.entry(
				ErrorCode.SOCIAL_ACCOUNT_ALREADY_LINKED,
				"해당 소셜 계정 제공자가 이미 연결되어 있습니다."),
			Map.entry(
				ErrorCode.RATE_LIMIT_EXCEEDED,
				"요청 처리 한도를 초과했습니다. 잠시 후 다시 시도해 주세요."),
			Map.entry(
				ErrorCode.SERVICE_UNAVAILABLE,
				"현재 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
			Map.entry(ErrorCode.ASSISTANT_NOT_ENABLED, "AI 모임 도우미가 현재 활성화되지 않았습니다."),
			Map.entry(ErrorCode.ASSISTANT_CONSENT_REQUIRED, "외부 AI 처리 동의가 필요합니다."),
			Map.entry(ErrorCode.ASSISTANT_CONSENT_VERSION_MISMATCH, "최신 동의문을 확인해야 합니다."),
			Map.entry(ErrorCode.ASSISTANT_INPUT_NOT_ALLOWED, "외부 AI 처리에 허용되지 않는 입력입니다."),
			Map.entry(ErrorCode.ASSISTANT_PROVIDER_UNAVAILABLE, "AI provider를 현재 사용할 수 없습니다."),
			Map.entry(ErrorCode.ASSISTANT_PROVIDER_RESPONSE_INVALID, "AI provider 응답을 처리할 수 없습니다."),
			Map.entry(ErrorCode.ASSISTANT_COST_LIMIT_EXCEEDED, "AI 사용 비용 한도를 초과했습니다."),
			Map.entry(ErrorCode.GAME_NOT_FOUND, "게임을 찾을 수 없습니다."),
			Map.entry(ErrorCode.ROOM_NOT_FOUND, "방을 찾을 수 없습니다."),
			Map.entry(ErrorCode.NOTIFICATION_NOT_FOUND, "알림을 찾을 수 없습니다."),
			Map.entry(ErrorCode.INVALID_ROOM_STATUS_TRANSITION, "허용되지 않은 방 상태 변경입니다."),
			Map.entry(
				ErrorCode.ROOM_UPDATE_NOT_ALLOWED_WITH_ACTIVE_PARTICIPANTS,
				"주최자 외 활성 참가자가 있는 방은 수정할 수 없습니다."),
			Map.entry(
				ErrorCode.ROOM_CONCURRENT_MODIFICATION,
				"방 정보가 동시에 변경되었습니다. 다시 시도해 주세요."),
			Map.entry(ErrorCode.PARTICIPATION_NOT_FOUND, "현재 참가 정보를 찾을 수 없습니다."),
			Map.entry(ErrorCode.CAPACITY_EXCEEDED, "모집 가능한 인원을 초과했습니다."),
			Map.entry(ErrorCode.ROOM_NOT_RECRUITING, "현재 모집 중인 방이 아닙니다."),
			Map.entry(ErrorCode.ALREADY_PARTICIPATING, "이미 참가 중인 방입니다."),
			Map.entry(ErrorCode.WAITLIST_NOT_AVAILABLE, "현재 대기 등록을 할 수 없습니다."),
			Map.entry(ErrorCode.WAITLIST_ENTRY_NOT_FOUND, "현재 대기 정보를 찾을 수 없습니다."),
			Map.entry(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, "동일한 멱등성 키를 다른 요청에 사용할 수 없습니다."),
			Map.entry(ErrorCode.MATCH_CURRENT_STATE_NOT_STABLE, "매칭 현재 상태가 계속 변경 중입니다. 잠시 후 다시 시도해 주세요."),
			Map.entry(ErrorCode.MATCH_REQUEST_ALREADY_ACTIVE, "이미 진행 중인 매칭 요청이 있습니다."),
			Map.entry(ErrorCode.MATCH_REQUEST_CANCELLATION_NOT_AVAILABLE, "현재 성공 파티는 매칭 요청으로 취소할 수 없습니다."),
			Map.entry(ErrorCode.MATCH_PROPOSAL_RESPONSE_NOT_AVAILABLE, "현재 응답할 수 있는 매칭 제안이 없습니다."),
			Map.entry(ErrorCode.MATCH_PARTY_NOT_FOUND, "성공 파티를 찾을 수 없습니다."),
			Map.entry(ErrorCode.MATCH_PARTY_LEAVE_NOT_AVAILABLE, "현재 성공 파티에서 나갈 수 없습니다."),
			Map.entry(ErrorCode.MATCH_CHAT_NOT_ACTIVE, "매칭 채팅이 아직 준비되지 않았습니다."),
			Map.entry(ErrorCode.MATCH_PARTICIPANT_NOT_FOUND, "매칭 참가자를 찾을 수 없습니다."),
			Map.entry(ErrorCode.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."));
		Map<ErrorCode, HttpStatus> expectedStatuses = Map.ofEntries(
			Map.entry(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST),
			Map.entry(ErrorCode.METHOD_NOT_ALLOWED, HttpStatus.METHOD_NOT_ALLOWED),
			Map.entry(ErrorCode.NOT_ACCEPTABLE, HttpStatus.NOT_ACCEPTABLE),
			Map.entry(
				ErrorCode.UNSUPPORTED_MEDIA_TYPE,
				HttpStatus.UNSUPPORTED_MEDIA_TYPE),
			Map.entry(ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND),
			Map.entry(ErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED),
			Map.entry(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN),
			Map.entry(ErrorCode.CSRF_TOKEN_INVALID, HttpStatus.FORBIDDEN),
			Map.entry(ErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED),
			Map.entry(ErrorCode.EMAIL_ALREADY_EXISTS, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.SOCIAL_PROVIDER_NOT_AVAILABLE, HttpStatus.NOT_FOUND),
			Map.entry(ErrorCode.SOCIAL_ACCOUNT_ALREADY_LINKED, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.RATE_LIMIT_EXCEEDED, HttpStatus.TOO_MANY_REQUESTS),
			Map.entry(ErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE),
			Map.entry(ErrorCode.ASSISTANT_NOT_ENABLED, HttpStatus.SERVICE_UNAVAILABLE),
			Map.entry(ErrorCode.ASSISTANT_CONSENT_REQUIRED, HttpStatus.FORBIDDEN),
			Map.entry(ErrorCode.ASSISTANT_CONSENT_VERSION_MISMATCH, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.ASSISTANT_INPUT_NOT_ALLOWED, HttpStatus.BAD_REQUEST),
			Map.entry(ErrorCode.ASSISTANT_PROVIDER_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE),
			Map.entry(ErrorCode.ASSISTANT_PROVIDER_RESPONSE_INVALID, HttpStatus.SERVICE_UNAVAILABLE),
			Map.entry(ErrorCode.ASSISTANT_COST_LIMIT_EXCEEDED, HttpStatus.TOO_MANY_REQUESTS),
			Map.entry(ErrorCode.GAME_NOT_FOUND, HttpStatus.NOT_FOUND),
			Map.entry(ErrorCode.ROOM_NOT_FOUND, HttpStatus.NOT_FOUND),
			Map.entry(ErrorCode.NOTIFICATION_NOT_FOUND, HttpStatus.NOT_FOUND),
			Map.entry(ErrorCode.INVALID_ROOM_STATUS_TRANSITION, HttpStatus.CONFLICT),
			Map.entry(
				ErrorCode.ROOM_UPDATE_NOT_ALLOWED_WITH_ACTIVE_PARTICIPANTS,
				HttpStatus.CONFLICT),
			Map.entry(ErrorCode.ROOM_CONCURRENT_MODIFICATION, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.PARTICIPATION_NOT_FOUND, HttpStatus.NOT_FOUND),
			Map.entry(ErrorCode.CAPACITY_EXCEEDED, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.ROOM_NOT_RECRUITING, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.ALREADY_PARTICIPATING, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.WAITLIST_NOT_AVAILABLE, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.WAITLIST_ENTRY_NOT_FOUND, HttpStatus.NOT_FOUND),
			Map.entry(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.MATCH_CURRENT_STATE_NOT_STABLE, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.MATCH_REQUEST_ALREADY_ACTIVE, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.MATCH_REQUEST_CANCELLATION_NOT_AVAILABLE, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.MATCH_PROPOSAL_RESPONSE_NOT_AVAILABLE, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.MATCH_PARTY_NOT_FOUND, HttpStatus.NOT_FOUND),
			Map.entry(ErrorCode.MATCH_PARTY_LEAVE_NOT_AVAILABLE, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.MATCH_CHAT_NOT_ACTIVE, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.MATCH_PARTICIPANT_NOT_FOUND, HttpStatus.NOT_FOUND),
			Map.entry(
				ErrorCode.INTERNAL_SERVER_ERROR, HttpStatus.INTERNAL_SERVER_ERROR));

		assertEquals(expectedMessages.keySet().size(), ErrorCode.values().length);
		assertEquals(expectedStatuses.keySet().size(), ErrorCode.values().length);
		expectedMessages.forEach(
			(code, message) -> {
				assertEquals(code.name(), code.getCode());
				assertEquals(message, code.getMessage());
				assertEquals(expectedStatuses.get(code), code.getHttpStatus());
				assertNotNull(code.getHttpStatus());
				assertEquals(code.getHttpStatus().value(), code.getStatus());
			});
		assertEquals(
			HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus());
	}

	@Test
	void MATCH_오류_코드는_API_계약의_HTTP_상태와_기본_메시지를_제공한다() {
		Map<String, HttpStatus> expectedStatuses = Map.of(
			"IDEMPOTENCY_KEY_CONFLICT", HttpStatus.CONFLICT,
			"MATCH_CURRENT_STATE_NOT_STABLE", HttpStatus.CONFLICT,
			"MATCH_REQUEST_ALREADY_ACTIVE", HttpStatus.CONFLICT,
			"MATCH_REQUEST_CANCELLATION_NOT_AVAILABLE", HttpStatus.CONFLICT,
			"MATCH_PROPOSAL_RESPONSE_NOT_AVAILABLE", HttpStatus.CONFLICT,
			"MATCH_PARTY_NOT_FOUND", HttpStatus.NOT_FOUND,
			"MATCH_PARTY_LEAVE_NOT_AVAILABLE", HttpStatus.CONFLICT,
			"MATCH_CHAT_NOT_ACTIVE", HttpStatus.CONFLICT,
			"MATCH_PARTICIPANT_NOT_FOUND", HttpStatus.NOT_FOUND);
		Map<String, String> expectedMessages = Map.of(
			"IDEMPOTENCY_KEY_CONFLICT", "동일한 멱등성 키를 다른 요청에 사용할 수 없습니다.",
			"MATCH_CURRENT_STATE_NOT_STABLE", "매칭 현재 상태가 계속 변경 중입니다. 잠시 후 다시 시도해 주세요.",
			"MATCH_REQUEST_ALREADY_ACTIVE", "이미 진행 중인 매칭 요청이 있습니다.",
			"MATCH_REQUEST_CANCELLATION_NOT_AVAILABLE", "현재 성공 파티는 매칭 요청으로 취소할 수 없습니다.",
			"MATCH_PROPOSAL_RESPONSE_NOT_AVAILABLE", "현재 응답할 수 있는 매칭 제안이 없습니다.",
			"MATCH_PARTY_NOT_FOUND", "성공 파티를 찾을 수 없습니다.",
			"MATCH_PARTY_LEAVE_NOT_AVAILABLE", "현재 성공 파티에서 나갈 수 없습니다.",
			"MATCH_CHAT_NOT_ACTIVE", "매칭 채팅이 아직 준비되지 않았습니다.",
			"MATCH_PARTICIPANT_NOT_FOUND", "매칭 참가자를 찾을 수 없습니다.");

		assertEquals(9, expectedStatuses.size());
		List.copyOf(expectedStatuses.keySet()).forEach(
			name -> {
				ErrorCode code = ErrorCode.valueOf(name);
				assertEquals(expectedStatuses.get(name), code.getHttpStatus());
				assertEquals(expectedMessages.get(name), code.getMessage());
			});
	}
}
