package cloud.bamsongi.albammate.auth.validation;

import cloud.bamsongi.albammate.auth.dto.ProfileUpdateRequest;
import cloud.bamsongi.albammate.auth.exception.ProfileValidationException;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** 프로필 닉네임의 정규화와 길이·문자 규칙을 검증한다. */
public final class ProfileUpdateRequestValidator
        implements ConstraintValidator<ValidProfileUpdateRequest, ProfileUpdateRequest> {

    @Override
    public boolean isValid(ProfileUpdateRequest value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        try {
            value.normalizeAndValidate();
            return true;
        } catch (ProfileValidationException exception) {
            return false;
        }
    }
}
