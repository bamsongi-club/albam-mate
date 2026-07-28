package cloud.bamsongi.albammate.auth.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 공백 제거와 소문자 변환 뒤의 이메일 형식을 검증한다. */
@Documented
@Constraint(validatedBy = EmailValidator.class)
@Target({
    ElementType.FIELD,
    ElementType.METHOD,
    ElementType.PARAMETER,
    ElementType.ANNOTATION_TYPE,
    ElementType.TYPE_USE,
    ElementType.RECORD_COMPONENT
})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidEmail {

    String message() default "유효한 이메일 형식이 아닙니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
