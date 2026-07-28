package cloud.bamsongi.albammate.auth.validation;

import cloud.bamsongi.albammate.user.contract.UserPasswordPolicy;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** 비밀번호 길이 정책을 Bean Validation과 서비스 직접 호출에 함께 제공한다. */
public final class PasswordValidator implements ConstraintValidator<ValidPassword, String> {

    private int minCodePoints;

    @Override
    public void initialize(ValidPassword constraintAnnotation) {
        minCodePoints = constraintAnnotation.minCodePoints();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || isValid(value, minCodePoints);
    }

    public static boolean isValid(String value, int minCodePoints) {
        if (value == null) {
            return false;
        }
        return UserPasswordPolicy.isValid(value, minCodePoints);
    }
}
