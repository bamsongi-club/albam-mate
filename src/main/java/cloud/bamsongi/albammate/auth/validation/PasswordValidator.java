package cloud.bamsongi.albammate.auth.validation;

import cloud.bamsongi.albammate.user.contract.UserPasswordPolicy;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** 사용자 모듈이 소유한 비밀번호 길이 정책으로 HTTP 입력을 검증한다. */
public final class PasswordValidator implements ConstraintValidator<ValidPassword, String> {

	private int minCodePoints;

	@Override
	public void initialize(ValidPassword constraintAnnotation) {
		minCodePoints = constraintAnnotation.minCodePoints();
	}

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		return value == null || UserPasswordPolicy.isValid(value, minCodePoints);
	}
}
