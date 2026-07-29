package cloud.bamsongi.albammate.global.security.password;

import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.global.config.AuthenticationRequestProtectionProperties;

/** 단일 애플리케이션 인스턴스에서 해시 작업 수를 제한한다. */
@Component
public class InMemoryPasswordHashConcurrencyLimiter implements PasswordHashConcurrencyLimiter {

	private final Semaphore slots;
	private final int maxConcurrent;

	public InMemoryPasswordHashConcurrencyLimiter(
		AuthenticationRequestProtectionProperties properties) {
		properties.validate();
		this.maxConcurrent = properties.getHashSlots();
		this.slots = new Semaphore(maxConcurrent, true);
	}

	@Override
	public Optional<PasswordHashPermit> tryAcquire() {
		if (!slots.tryAcquire()) {
			return Optional.empty();
		}
		return Optional.of(new Permit(slots));
	}

	@Override
	public int maxConcurrent() {
		return maxConcurrent;
	}

	@Override
	public int currentConcurrent() {
		return maxConcurrent - slots.availablePermits();
	}

	private static final class Permit implements PasswordHashPermit {

		private final Semaphore slots;
		private final AtomicBoolean released = new AtomicBoolean();

		private Permit(Semaphore slots) {
			this.slots = slots;
		}

		@Override
		public void close() {
			if (released.compareAndSet(false, true)) {
				slots.release();
			}
		}
	}
}
