package cloud.bamsongi.albammate.global.security.password;

import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;
import cloud.bamsongi.albammate.measurement.AuthNotificationMeasurementRecorder;

/** 슬롯을 얻은 경우에만 해시 작업 콜백을 실행하고 모든 경로에서 슬롯을 반환한다. */
@Component
public class PasswordHashExecutor {

	private final PasswordHashConcurrencyLimiter limiter;
	private final AuthNotificationMeasurementRecorder measurementRecorder;

	public PasswordHashExecutor(
		PasswordHashConcurrencyLimiter limiter, @Nullable AuthNotificationMeasurementRecorder measurementRecorder) {
		this.limiter = Objects.requireNonNull(limiter, "limiter");
		this.measurementRecorder = measurementRecorder;
	}

	public <T> T execute(Supplier<T> hashWork) {
		Objects.requireNonNull(hashWork, "hashWork");
		PasswordHashPermit permit;
		try {
			permit = limiter.tryAcquire().orElseThrow(() -> new RateLimitExceededException(1));
		} catch (RateLimitExceededException exception) {
			if (measurementRecorder != null) {
				measurementRecorder.authRejection("bcrypt-slot");
			}
			throw exception;
		}
		return measureWithPermit(permit, hashWork);
	}

	private <T> T measureWithPermit(PasswordHashPermit permit, Supplier<T> hashWork) {
		return measure("bcrypt-permit", () -> {
			try {
				return hashWork.get();
			} finally {
				permit.close();
			}
		});
	}

	private <T> T measure(String stage, Supplier<T> work) {
		return measurementRecorder == null ? work.get() : measurementRecorder.authStage(stage, work);
	}
}
