package cloud.bamsongi.albammate.global.security;

import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** 슬롯을 얻은 경우에만 해시 작업 콜백을 실행하고 모든 경로에서 슬롯을 반환한다. */
@Component
public class PasswordHashExecutor {

    private final PasswordHashConcurrencyLimiter limiter;

    public PasswordHashExecutor(PasswordHashConcurrencyLimiter limiter) {
        this.limiter = Objects.requireNonNull(limiter, "limiter");
    }

    public <T> T execute(Supplier<T> hashWork) {
        Objects.requireNonNull(hashWork, "hashWork");
        PasswordHashPermit permit =
                limiter.tryAcquire().orElseThrow(() -> new RateLimitExceededException(1));
        try {
            return hashWork.get();
        } finally {
            permit.close();
        }
    }

    public void execute(Runnable hashWork) {
        execute(
                () -> {
                    hashWork.run();
                    return null;
                });
    }
}
