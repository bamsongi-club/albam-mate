package cloud.bamsongi.albammate.global.exception;

import java.util.Objects;

import lombok.Getter;

/** 예상 가능한 도메인 또는 애플리케이션 실패를 표현하는 공통 예외다. */
@Getter
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	public BusinessException(ErrorCode errorCode) {
		super(Objects.requireNonNull(errorCode, "errorCode").getMessage());
		this.errorCode = errorCode;
	}

	public BusinessException(ErrorCode errorCode, Throwable cause) {
		super(Objects.requireNonNull(errorCode, "errorCode").getMessage(), cause);
		this.errorCode = errorCode;
	}
}
