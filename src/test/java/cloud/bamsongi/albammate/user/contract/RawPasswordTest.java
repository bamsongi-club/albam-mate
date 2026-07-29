package cloud.bamsongi.albammate.user.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RawPasswordTest {

    @Test
    void 회원가입_정책을_만족하는_비밀번호만_생성한다() {
        assertEquals("123456789012345", RawPassword.from("123456789012345").orElseThrow().value());
        assertTrue(RawPassword.from(null).isEmpty());
        assertTrue(RawPassword.from("12345678901234").isEmpty());
        assertTrue(RawPassword.from("a".repeat(65)).isEmpty());
        assertTrue(RawPassword.from("가".repeat(23) + "é".repeat(2)).isEmpty());
    }

    @Test
    void 문자열_표현은_비밀번호_원문을_노출하지_않고_값_동등성을_가진다() {
        String password = "sensitive-password";
        RawPassword first = RawPassword.from(password).orElseThrow();
        RawPassword samePassword = RawPassword.from(password).orElseThrow();
        RawPassword differentPassword = RawPassword.from("another-password-123").orElseThrow();

        assertFalse(first.toString().contains(password));
        assertEquals(first, first);
        assertEquals(first, samePassword);
        assertEquals(first.hashCode(), samePassword.hashCode());
        assertEquals(0, first.hashCode());
        assertNotEquals(first, differentPassword);
        assertNotEquals(first, password);
    }
}
