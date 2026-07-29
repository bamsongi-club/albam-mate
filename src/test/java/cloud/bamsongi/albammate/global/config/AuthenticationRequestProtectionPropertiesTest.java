package cloud.bamsongi.albammate.global.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AuthenticationRequestProtectionPropertiesTest {

    @Test
    void 기본값은_유효한_인증_요청_보호_설정이다() {
        assertDoesNotThrow(() -> new AuthenticationRequestProtectionProperties().validate());
    }

    @Test
    void window은_null_zero_음수를_거절한다() {
        for (Duration window : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            AuthenticationRequestProtectionProperties properties =
                    new AuthenticationRequestProtectionProperties();
            properties.setWindow(window);

            assertThrows(IllegalArgumentException.class, properties::validate);
        }
    }

    @Test
    void 요청_한도와_상태_상한과_hash_slots는_0_이하를_거절한다() {
        assertInvalid(properties -> properties.setSignupLimit(0));
        assertInvalid(properties -> properties.setLoginLimit(-1));
        assertInvalid(properties -> properties.setLoginFailureLimit(0));
        assertInvalid(properties -> properties.setMaxIpKeys(-1));
        assertInvalid(properties -> properties.setMaxFailureKeys(0));
        assertInvalid(properties -> properties.setHashSlots(-1));
    }

    private void assertInvalid(
            java.util.function.Consumer<AuthenticationRequestProtectionProperties> change) {
        AuthenticationRequestProtectionProperties properties =
                new AuthenticationRequestProtectionProperties();
        change.accept(properties);

        assertThrows(IllegalArgumentException.class, properties::validate);
    }
}
