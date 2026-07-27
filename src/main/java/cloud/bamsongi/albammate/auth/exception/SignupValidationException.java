package cloud.bamsongi.albammate.auth.exception;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;

/** 회원가입 입력이 정본의 형식·길이·문자 규칙을 통과하지 못한 경우다. */
public final class SignupValidationException extends BusinessException {

    public SignupValidationException() {
        super(ErrorCode.VALIDATION_ERROR);
    }
}
