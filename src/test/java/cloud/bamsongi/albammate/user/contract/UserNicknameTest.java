package cloud.bamsongi.albammate.user.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UserNicknameTest {

    @Test
    void null_닉네임은_빈_Optional을_반환한다() {
        assertTrue(UserNickname.from(null).isEmpty());
    }

    @Test
    void 같은_정규화된_닉네임은_값_동등성을_가진다() {
        UserNickname nickname = UserNickname.from(" 닉네임 ").orElseThrow();
        UserNickname sameNickname = UserNickname.from("닉네임").orElseThrow();

        assertEquals(nickname, nickname);
        assertEquals(nickname, sameNickname);
        assertEquals(nickname.hashCode(), sameNickname.hashCode());
        assertNotEquals(nickname, UserNickname.from("다른 닉네임").orElseThrow());
        assertNotEquals(nickname, "닉네임");
    }

    @Test
    void 빈값_최대길이초과_제어문자_닉네임은_거절한다() {
        assertTrue(UserNickname.from(" ").isEmpty());
        assertTrue(UserNickname.from("가".repeat(51)).isEmpty());
        assertTrue(UserNickname.from("닉\n네임").isEmpty());
    }
}
