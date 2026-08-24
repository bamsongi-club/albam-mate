package cloud.bamsongi.albammate.auth.validation;

import cloud.bamsongi.albammate.user.contract.UserEmail;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** 공유 이메일 정규화 규칙으로 HTTP 입력을 검증한다. */
public final class EmailValidator implements ConstraintValidator<ValidEmail, String> {

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		return value == null || UserEmail.from(value).isPresent();
	}
}
