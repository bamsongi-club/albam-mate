package cloud.bamsongi.albammate.global.exception;

import org.springframework.http.HttpStatus;

/** HTTP API가 외부에 노출하는 안정적인 오류 코드 카탈로그다. */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "요청값 검증에 실패했습니다."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "요청을 수행할 권한이 없습니다."),
    CSRF_TOKEN_INVALID(HttpStatus.FORBIDDEN, "CSRF 토큰이 없거나 유효하지 않습니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 일치하지 않습니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "인증 요청 처리 한도를 초과했습니다. 잠시 후 다시 시도해 주세요."),
    GAME_NOT_FOUND(HttpStatus.NOT_FOUND, "게임을 찾을 수 없습니다."),
    ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "방을 찾을 수 없습니다."),
    INVALID_ROOM_STATUS_TRANSITION(HttpStatus.CONFLICT, "허용되지 않은 방 상태 변경입니다."),
    ROOM_UPDATE_NOT_ALLOWED_WITH_ACTIVE_PARTICIPANTS(
            HttpStatus.CONFLICT, "주최자 외 활성 참가자가 있는 방은 수정할 수 없습니다."),
    ROOM_CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "방 정보가 동시에 변경되었습니다. 다시 시도해 주세요."),
    PARTICIPATION_NOT_FOUND(HttpStatus.NOT_FOUND, "현재 참가 정보를 찾을 수 없습니다."),
    CAPACITY_EXCEEDED(HttpStatus.CONFLICT, "모집 가능한 인원을 초과했습니다."),
    ROOM_NOT_RECRUITING(HttpStatus.CONFLICT, "현재 모집 중인 방이 아닙니다."),
    ALREADY_PARTICIPATING(HttpStatus.CONFLICT, "이미 참가 중인 방입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public int getStatus() {
        return httpStatus.value();
    }

    public String getCode() {
        return name();
    }

    public String getMessage() {
        return message;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public int status() {
        return getStatus();
    }

    public String code() {
        return getCode();
    }

    public String message() {
        return message;
    }
}
