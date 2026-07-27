package cloud.bamsongi.albammate.auth.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 정규화 이후의 회원가입 입력 규칙을 Bean Validation 경계에서 확인한다. */
@Documented
@Constraint(validatedBy = SignupRequestValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidSignupRequest {

    String message() default "요청값 검증에 실패했습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
