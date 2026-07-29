package cloud.bamsongi.albammate.auth.exception;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;

/** 로그인 입력이 정본의 형식·길이 규칙을 통과하지 못한 경우다. */
public final class LoginValidationException extends BusinessException {

	public LoginValidationException() {
		super(ErrorCode.VALIDATION_ERROR);
	}
}
