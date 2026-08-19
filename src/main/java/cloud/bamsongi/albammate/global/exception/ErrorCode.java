package cloud.bamsongi.albammate.global.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

/** HTTP API가 외부에 노출하는 안정적인 오류 코드 카탈로그다. */
@Getter
public enum ErrorCode {
	VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "요청값 검증에 실패했습니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 HTTP 메서드입니다."),
	NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "요청한 응답 미디어 타입을 제공할 수 없습니다."),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 미디어 타입입니다."),
	RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
	FORBIDDEN(HttpStatus.FORBIDDEN, "요청을 수행할 권한이 없습니다."),
	CSRF_TOKEN_INVALID(HttpStatus.FORBIDDEN, "CSRF 토큰이 없거나 유효하지 않습니다."),
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 일치하지 않습니다."),
	EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
	SOCIAL_PROVIDER_NOT_AVAILABLE(HttpStatus.NOT_FOUND, "사용할 수 없는 소셜 로그인 제공자입니다."),
	SOCIAL_ACCOUNT_ALREADY_LINKED(HttpStatus.CONFLICT, "해당 소셜 계정 제공자가 이미 연결되어 있습니다."),
	RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "인증 요청 처리 한도를 초과했습니다. 잠시 후 다시 시도해 주세요."),
	SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "현재 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
	ASSISTANT_NOT_ENABLED(HttpStatus.SERVICE_UNAVAILABLE, "AI 모임 도우미가 현재 활성화되지 않았습니다."),
	ASSISTANT_CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "외부 AI 처리 동의가 필요합니다."),
	ASSISTANT_CONSENT_VERSION_MISMATCH(HttpStatus.CONFLICT, "최신 동의문을 확인해야 합니다."),
	GAME_NOT_FOUND(HttpStatus.NOT_FOUND, "게임을 찾을 수 없습니다."),
	ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "방을 찾을 수 없습니다."),
	NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),
	INVALID_ROOM_STATUS_TRANSITION(HttpStatus.CONFLICT, "허용되지 않은 방 상태 변경입니다."),
	ROOM_UPDATE_NOT_ALLOWED_WITH_ACTIVE_PARTICIPANTS(
		HttpStatus.CONFLICT, "주최자 외 활성 참가자가 있는 방은 수정할 수 없습니다."),
	ROOM_CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "방 정보가 동시에 변경되었습니다. 다시 시도해 주세요."),
	PARTICIPATION_NOT_FOUND(HttpStatus.NOT_FOUND, "현재 참가 정보를 찾을 수 없습니다."),
	CAPACITY_EXCEEDED(HttpStatus.CONFLICT, "모집 가능한 인원을 초과했습니다."),
	ROOM_NOT_RECRUITING(HttpStatus.CONFLICT, "현재 모집 중인 방이 아닙니다."),
	ALREADY_PARTICIPATING(HttpStatus.CONFLICT, "이미 참가 중인 방입니다."),
	WAITLIST_NOT_AVAILABLE(HttpStatus.CONFLICT, "현재 대기 등록을 할 수 없습니다."),
	WAITLIST_ENTRY_NOT_FOUND(HttpStatus.NOT_FOUND, "현재 대기 정보를 찾을 수 없습니다."),
	IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "동일한 멱등성 키를 다른 요청에 사용할 수 없습니다."),
	MATCH_CURRENT_STATE_NOT_STABLE(HttpStatus.CONFLICT, "매칭 현재 상태가 계속 변경 중입니다. 잠시 후 다시 시도해 주세요."),
	MATCH_REQUEST_ALREADY_ACTIVE(HttpStatus.CONFLICT, "이미 진행 중인 매칭 요청이 있습니다."),
	MATCH_REQUEST_CANCELLATION_NOT_AVAILABLE(HttpStatus.CONFLICT, "현재 성공 파티는 매칭 요청으로 취소할 수 없습니다."),
	MATCH_PROPOSAL_RESPONSE_NOT_AVAILABLE(HttpStatus.CONFLICT, "현재 응답할 수 있는 매칭 제안이 없습니다."),
	MATCH_PARTY_NOT_FOUND(HttpStatus.NOT_FOUND, "성공 파티를 찾을 수 없습니다."),
	MATCH_PARTY_LEAVE_NOT_AVAILABLE(HttpStatus.CONFLICT, "현재 성공 파티에서 나갈 수 없습니다."),
	MATCH_CHAT_NOT_ACTIVE(HttpStatus.CONFLICT, "매칭 채팅이 아직 준비되지 않았습니다."),
	MATCH_PARTICIPANT_NOT_FOUND(HttpStatus.NOT_FOUND, "매칭 참가자를 찾을 수 없습니다."),
	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

	private final HttpStatus httpStatus;
	private final String message;

	ErrorCode(HttpStatus httpStatus, String message) {
		this.httpStatus = httpStatus;
		this.message = message;
	}

	public int getStatus() {
		return httpStatus.value();
	}

	public String getCode() {
		return name();
	}
}
