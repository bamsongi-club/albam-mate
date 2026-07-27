package cloud.bamsongi.albammate.auth.exception;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;

/** 계정 존재 여부를 노출하지 않고 로그인 자격증명 불일치를 전달한다. */
public final class InvalidCredentialsException extends BusinessException {

    public InvalidCredentialsException() {
        super(ErrorCode.INVALID_CREDENTIALS);
    }
}
