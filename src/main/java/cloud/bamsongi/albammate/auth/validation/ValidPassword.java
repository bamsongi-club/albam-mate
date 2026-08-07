package cloud.bamsongi.albammate.auth.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** 비밀번호의 Unicode code point와 UTF-8 바이트 길이를 검증한다. */
@Documented
@Constraint(validatedBy = PasswordValidator.class)
@Target({
	ElementType.FIELD,
	ElementType.METHOD,
	ElementType.PARAMETER,
	ElementType.ANNOTATION_TYPE,
	ElementType.TYPE_USE,
	ElementType.RECORD_COMPONENT
})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword{

	int minCodePoints() default 1;

	boolean signup() default false;

	String message() default "유효한 비밀번호 길이가 아닙니다.";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
