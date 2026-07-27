package cloud.bamsongi.albammate.user.exception;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;

/** 정규화된 이메일이 이미 사용자 계정에 등록된 경우다. */
public final class EmailAlreadyExistsException extends BusinessException {

    public EmailAlreadyExistsException() {
        super(ErrorCode.EMAIL_ALREADY_EXISTS);
    }

    public EmailAlreadyExistsException(Throwable cause) {
        super(ErrorCode.EMAIL_ALREADY_EXISTS, cause);
    }
}
