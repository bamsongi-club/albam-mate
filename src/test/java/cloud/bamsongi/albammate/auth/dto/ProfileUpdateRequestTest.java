package cloud.bamsongi.albammate.auth.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class ProfileUpdateRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void 닉네임의_앞뒤_공백을_제거하고_유니코드_code_point_기준_경계를_허용한다() {
        assertEquals("닉네임", new ProfileUpdateRequest(" 닉네임 ").normalize().nickname());
        assertEquals(
                50,
                new ProfileUpdateRequest("😀".repeat(50))
                        .normalize()
                        .nickname()
                        .codePointCount(0, 100));
    }

    @Test
    void record_컴포넌트_제약이_필수값과_닉네임_정규화_규칙을_검증한다() {
        String supplementaryPlaneNickname = "😀".repeat(50);

        assertEquals(
                50,
                supplementaryPlaneNickname.codePointCount(0, supplementaryPlaneNickname.length()));
        assertTrue(
                validator.validate(new ProfileUpdateRequest(supplementaryPlaneNickname)).isEmpty());
        assertFalse(validator.validate(new ProfileUpdateRequest(null)).isEmpty());
        assertFalse(validator.validate(new ProfileUpdateRequest("   ")).isEmpty());
        assertFalse(validator.validate(new ProfileUpdateRequest("😀".repeat(51))).isEmpty());
        assertFalse(validator.validate(new ProfileUpdateRequest("닉\n네임")).isEmpty());
    }
}
