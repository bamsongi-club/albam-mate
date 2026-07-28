package cloud.bamsongi.albammate.auth.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cloud.bamsongi.albammate.auth.exception.ProfileValidationException;
import org.junit.jupiter.api.Test;

class ProfileUpdateRequestTest {

    @Test
    void 닉네임의_앞뒤_공백을_제거하고_유니코드_code_point_기준_경계를_허용한다() {
        assertEquals("닉네임", new ProfileUpdateRequest(" 닉네임 ").normalizeAndValidate().nickname());
        assertEquals(
                50,
                new ProfileUpdateRequest("😀".repeat(50))
                        .normalizeAndValidate()
                        .nickname()
                        .codePointCount(0, 100));
    }

    @Test
    void null_빈값_범위초과와_제어문자를_거절한다() {
        assertThrows(
                ProfileValidationException.class,
                () -> new ProfileUpdateRequest(null).normalizeAndValidate());
        assertThrows(
                ProfileValidationException.class,
                () -> new ProfileUpdateRequest("").normalizeAndValidate());
        assertThrows(
                ProfileValidationException.class,
                () -> new ProfileUpdateRequest("   ").normalizeAndValidate());
        assertThrows(
                ProfileValidationException.class,
                () -> new ProfileUpdateRequest("가".repeat(51)).normalizeAndValidate());
        assertThrows(
                ProfileValidationException.class,
                () -> new ProfileUpdateRequest("😀".repeat(51)).normalizeAndValidate());
        assertThrows(
                ProfileValidationException.class,
                () -> new ProfileUpdateRequest("닉\n네임").normalizeAndValidate());
    }
}
