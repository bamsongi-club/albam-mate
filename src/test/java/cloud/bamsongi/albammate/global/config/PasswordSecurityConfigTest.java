package cloud.bamsongi.albammate.global.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordSecurityConfigTest {

    private final PasswordSecurityConfig config = new PasswordSecurityConfig();

    @Test
    void 신규_비밀번호_해시는_bcrypt_식별자를_포함하고_원문과_다르다() {
        PasswordEncoder encoder = config.passwordEncoder(properties(10));

        String first = encoder.encode("correct horse battery staple");
        String second = encoder.encode("correct horse battery staple");

        assertTrue(first.startsWith("{bcrypt}"));
        assertNotEquals(first, second);
        assertTrue(encoder.matches("correct horse battery staple", first));
    }

    @Test
    void 비용을_올리면_기존_해시에_upgradeEncoding을_적용할_수_있다() {
        PasswordEncoder oldEncoder = config.passwordEncoder(properties(10));
        PasswordEncoder currentEncoder = config.passwordEncoder(properties(11));
        String oldHash = oldEncoder.encode("password");

        assertTrue(currentEncoder.matches("password", oldHash));
        assertTrue(currentEncoder.upgradeEncoding(oldHash));
        assertEquals(false, currentEncoder.upgradeEncoding(currentEncoder.encode("password")));
    }

    @Test
    void bcrypt_cost는_10_미만이나_31_초과로_설정할_수_없다() {
        PasswordSecurityProperties low = properties(9);
        PasswordSecurityProperties high = properties(32);

        assertThrows(IllegalArgumentException.class, () -> config.passwordEncoder(low));
        assertThrows(IllegalArgumentException.class, () -> config.passwordEncoder(high));
    }

    private PasswordSecurityProperties properties(int cost) {
        PasswordSecurityProperties properties = new PasswordSecurityProperties();
        properties.setBcryptCost(cost);
        return properties;
    }
}
