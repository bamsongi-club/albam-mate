package cloud.bamsongi.albammate.user.validation;

import cloud.bamsongi.albammate.user.contract.UserNickname;
import cloud.bamsongi.albammate.user.contract.ValidNickname;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** 사용자 모듈이 소유한 닉네임 불변식으로 HTTP 입력을 검증한다. */
public final class NicknameValidator implements ConstraintValidator<ValidNickname, String> {

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		return value == null || UserNickname.from(value).isPresent();
	}
}
