package cloud.bamsongi.albammate.user.exception;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;

/** 닉네임이 사용자 모듈의 정규화·형식 규칙을 통과하지 못한 경우다. */
public final class InvalidNicknameException extends BusinessException {

    public InvalidNicknameException() {
        super(ErrorCode.VALIDATION_ERROR);
    }
}
