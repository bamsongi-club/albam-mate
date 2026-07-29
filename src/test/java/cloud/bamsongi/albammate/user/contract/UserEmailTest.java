package cloud.bamsongi.albammate.user.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UserEmailTest {

    @Test
    void 앞뒤_공백을_제거하고_소문자로_정규화한다() {
        assertEquals(
                "user@example.com", UserEmail.from(" User@Example.COM ").orElseThrow().value());
    }

    @Test
    void null_빈값_제어문자와_내부_공백은_거절한다() {
        for (String email :
                new String[] {null, "", "  ", "user@exam\nple.com", "user @example.com"}) {
            assertTrue(UserEmail.from(email).isEmpty());
        }
    }

    @Test
    void 다중_at과_점_경계와_도메인_연속점은_거절한다() {
        for (String email :
                new String[] {
                    "user@@example.com",
                    "user.@example.com",
                    "user@.example.com",
                    "user@example..com"
                }) {
            assertTrue(UserEmail.from(email).isEmpty());
        }
    }

    @Test
    void 최대_255_code_point를_초과하는_이메일은_거절한다() {
        assertTrue(UserEmail.from("a@" + "d".repeat(253)).isPresent());
        assertTrue(UserEmail.from("a@" + "d".repeat(254)).isEmpty());
    }

    @Test
    void 보조_평면_문자를_포함해도_255_code_point는_허용하고_256은_거절한다() {
        String allowed = "😀".repeat(252) + "a@x";
        String rejected = "😀".repeat(252) + "aa@x";

        assertEquals(255, allowed.codePointCount(0, allowed.length()));
        assertEquals(256, rejected.codePointCount(0, rejected.length()));
        assertTrue(UserEmail.from(allowed).isPresent());
        assertTrue(UserEmail.from(rejected).isEmpty());
    }
}
