package cloud.bamsongi.albammate.user.contract;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class CreateUserAccountCommandTest {

    @Test
    void 문자열_표현은_비밀번호_원문을_노출하지_않는다() {
        String password = "sensitive-password";

        assertFalse(
                new CreateUserAccountCommand("user@example.com", password, "닉네임")
                        .toString()
                        .contains(password));
    }
}
