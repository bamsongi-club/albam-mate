package cloud.bamsongi.albammate.global.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class BusinessExceptionTest {

    @Test
    void BusinessException은_오류_코드의_기본_메시지만_공개한다() {
        IllegalStateException cause = new IllegalStateException("비밀번호=secret");
        BusinessException exception = new BusinessException(ErrorCode.INVALID_CREDENTIALS, cause);

        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
        assertEquals(ErrorCode.INVALID_CREDENTIALS.getMessage(), exception.getMessage());
        assertSame(cause, exception.getCause());
    }
}
