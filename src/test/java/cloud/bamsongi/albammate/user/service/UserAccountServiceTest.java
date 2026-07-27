package cloud.bamsongi.albammate.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;
import cloud.bamsongi.albammate.global.security.PasswordHashConcurrencyLimiter;
import cloud.bamsongi.albammate.global.security.PasswordHashExecutor;
import cloud.bamsongi.albammate.global.security.PasswordHashPermit;
import cloud.bamsongi.albammate.user.contract.UserAccount;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.exception.EmailAlreadyExistsException;
import cloud.bamsongi.albammate.user.repository.UserRepository;
import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserAccountServiceTest {

    @Mock private UserRepository userRepository;

    @Mock private PasswordEncoder passwordEncoder;

    @Test
    void 이메일을_먼저_중복확인하고_슬롯_안에서_해시해_계정을_저장한다() {
        PasswordHashConcurrencyLimiter limiter = new AlwaysAvailableLimiter();
        UserAccountApplicationService service =
                new UserAccountApplicationService(
                        userRepository, passwordEncoder, new PasswordHashExecutor(limiter));
        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode("123456789012345")).thenReturn("{bcrypt}encoded");
        when(userRepository.saveAndFlush(any(User.class)))
                .thenAnswer(
                        invocation -> {
                            User user = invocation.getArgument(0);
                            setId(user, 9L);
                            return user;
                        });

        UserAccount account = service.createAccount("user@example.com", "123456789012345", "닉네임");

        assertEquals(new UserAccount(9L, "닉네임"), account);
        verify(passwordEncoder).encode("123456789012345");
        verify(userRepository).saveAndFlush(any(User.class));
        assertEquals(0, limiter.currentConcurrent());
    }

    @Test
    void 사전_중복이면_해시와_저장을_수행하지_않는다() {
        PasswordHashConcurrencyLimiter limiter = new AlwaysAvailableLimiter();
        UserAccountApplicationService service =
                new UserAccountApplicationService(
                        userRepository, passwordEncoder, new PasswordHashExecutor(limiter));
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

        assertThrows(
                EmailAlreadyExistsException.class,
                () -> service.createAccount("user@example.com", "123456789012345", "닉네임"));

        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).saveAndFlush(any());
        assertEquals(0, limiter.currentConcurrent());
    }

    @Test
    void DB_unique_경쟁도_EMAIL_ALREADY_EXISTS로_변환한다() {
        PasswordHashConcurrencyLimiter limiter = new AlwaysAvailableLimiter();
        UserAccountApplicationService service =
                new UserAccountApplicationService(
                        userRepository, passwordEncoder, new PasswordHashExecutor(limiter));
        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode("123456789012345")).thenReturn("{bcrypt}encoded");
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("unique email"));

        assertThrows(
                EmailAlreadyExistsException.class,
                () -> service.createAccount("user@example.com", "123456789012345", "닉네임"));

        assertEquals(0, limiter.currentConcurrent());
    }

    @Test
    void 해시_슬롯이_없으면_사용자_생성을_시작하지_않는다() {
        PasswordHashConcurrencyLimiter limiter = new NoSlotLimiter();
        UserAccountApplicationService service =
                new UserAccountApplicationService(
                        userRepository, passwordEncoder, new PasswordHashExecutor(limiter));

        assertThrows(
                RateLimitExceededException.class,
                () -> service.createAccount("user@example.com", "123456789012345", "닉네임"));

        verify(userRepository, never()).existsByEmail(any());
        verify(passwordEncoder, never()).encode(any());
    }

    private static void setId(User user, long id) {
        try {
            Field field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class AlwaysAvailableLimiter implements PasswordHashConcurrencyLimiter {

        private int current;

        @Override
        public Optional<PasswordHashPermit> tryAcquire() {
            current++;
            return Optional.of(
                    () -> {
                        current--;
                    });
        }

        @Override
        public int maxConcurrent() {
            return 1;
        }

        @Override
        public int currentConcurrent() {
            return current;
        }
    }

    private static final class NoSlotLimiter implements PasswordHashConcurrencyLimiter {

        @Override
        public Optional<PasswordHashPermit> tryAcquire() {
            return Optional.empty();
        }

        @Override
        public int maxConcurrent() {
            return 1;
        }

        @Override
        public int currentConcurrent() {
            return 1;
        }
    }
}
