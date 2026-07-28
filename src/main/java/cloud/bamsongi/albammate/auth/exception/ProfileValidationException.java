package cloud.bamsongi.albammate.auth.exception;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;

/** 프로필 수정 입력이 형식·길이·문자 규칙을 통과하지 못한 경우다. */
public final class ProfileValidationException extends BusinessException {

    public ProfileValidationException() {
        super(ErrorCode.VALIDATION_ERROR);
    }
}
