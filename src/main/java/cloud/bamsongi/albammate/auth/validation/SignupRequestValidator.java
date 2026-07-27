package cloud.bamsongi.albammate.auth.validation;

import cloud.bamsongi.albammate.auth.dto.SignupRequest;
import cloud.bamsongi.albammate.auth.exception.SignupValidationException;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** 회원가입 요청의 정규화와 길이·문자·형식 규칙을 검증한다. */
public final class SignupRequestValidator
        implements ConstraintValidator<ValidSignupRequest, SignupRequest> {

    @Override
    public boolean isValid(SignupRequest value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        try {
            value.normalizeAndValidate();
            return true;
        } catch (SignupValidationException exception) {
            return false;
        }
    }
}
