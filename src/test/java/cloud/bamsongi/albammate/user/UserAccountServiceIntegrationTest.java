package cloud.bamsongi.albammate.user;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest
class UserAccountServiceIntegrationTest {

    @Autowired private UserAccountService userAccountService;

    @Autowired private UserRepository userRepository;

    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void 같은_비밀번호도_서로_다른_bcrypt_해시로_저장되고_검증된다() {
        String rawPassword = "123456789012345";
        String firstEmail = "hash-first@example.com";
        String secondEmail = "hash-second@example.com";

        userAccountService.createAccount(firstEmail, rawPassword, "첫 사용자");
        userAccountService.createAccount(secondEmail, rawPassword, "둘 사용자");

        User first = userRepository.findByEmail(firstEmail).orElseThrow();
        User second = userRepository.findByEmail(secondEmail).orElseThrow();

        assertTrue(first.getPasswordHash().startsWith("{bcrypt}"));
        assertTrue(second.getPasswordHash().startsWith("{bcrypt}"));
        assertNotEquals(first.getPasswordHash(), second.getPasswordHash());
        assertTrue(passwordEncoder.matches(rawPassword, first.getPasswordHash()));
        assertTrue(passwordEncoder.matches(rawPassword, second.getPasswordHash()));
    }
}
