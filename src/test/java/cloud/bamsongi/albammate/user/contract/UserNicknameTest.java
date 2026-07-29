package cloud.bamsongi.albammate.user.contract;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UserNicknameTest {

    @Test
    void null_닉네임은_빈_Optional을_반환한다() {
        assertTrue(UserNickname.from(null).isEmpty());
    }
}
