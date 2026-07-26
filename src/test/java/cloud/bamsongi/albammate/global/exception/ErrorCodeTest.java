package cloud.bamsongi.albammate.global.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ErrorCodeTest {

    @Test
    void API_오류_코드_카탈로그가_HTTP_상태와_기본_메시지를_함께_제공한다() {
        Map<ErrorCode, String> expectedMessages =
                Map.ofEntries(
                        Map.entry(ErrorCode.VALIDATION_ERROR, "요청값 검증에 실패했습니다."),
                        Map.entry(ErrorCode.UNAUTHENTICATED, "인증이 필요합니다."),
                        Map.entry(ErrorCode.FORBIDDEN, "요청을 수행할 권한이 없습니다."),
                        Map.entry(ErrorCode.CSRF_TOKEN_INVALID, "CSRF 토큰이 없거나 유효하지 않습니다."),
                        Map.entry(ErrorCode.INVALID_CREDENTIALS, "이메일 또는 비밀번호가 일치하지 않습니다."),
                        Map.entry(ErrorCode.EMAIL_ALREADY_EXISTS, "이미 사용 중인 이메일입니다."),
                        Map.entry(
                                ErrorCode.RATE_LIMIT_EXCEEDED,
                                "인증 요청 처리 한도를 초과했습니다. 잠시 후 다시 시도해 주세요."),
                        Map.entry(ErrorCode.GAME_NOT_FOUND, "게임을 찾을 수 없습니다."),
                        Map.entry(ErrorCode.ROOM_NOT_FOUND, "방을 찾을 수 없습니다."),
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
                        Map.entry(ErrorCode.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."));

        assertEquals(expectedMessages.keySet().size(), ErrorCode.values().length);
        expectedMessages.forEach(
                (code, message) -> {
                    assertEquals(code.name(), code.getCode());
                    assertEquals(message, code.getMessage());
                    assertNotNull(code.getHttpStatus());
                    assertEquals(code.getHttpStatus().value(), code.getStatus());
                });
        assertEquals(
                HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus());
    }
}
