package cloud.bamsongi.albammate.auth.validation;

import cloud.bamsongi.albammate.auth.dto.LoginRequest;
import cloud.bamsongi.albammate.auth.exception.LoginValidationException;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** 로그인 요청의 이메일 정규화와 비밀번호 길이·바이트 규칙을 검증한다. */
public final class LoginRequestValidator
        implements ConstraintValidator<ValidLoginRequest, LoginRequest> {

    @Override
    public boolean isValid(LoginRequest value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        try {
            value.normalizeAndValidate();
            return true;
        } catch (LoginValidationException exception) {
            return false;
        }
    }
}
