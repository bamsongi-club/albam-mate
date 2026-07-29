package cloud.bamsongi.albammate.global.security.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.global.config.AuthenticationRequestProtectionProperties;

/** 단일 애플리케이션 인스턴스에서 인증 요청을 이동 창으로 제한한다. */
@Component
public class InMemoryAuthenticationRequestLimiter implements AuthenticationRequestLimiter {

	private enum IpRequestType {
		SIGNUP,
		LOGIN
	}

	private record IpKey(IpRequestType type, String remoteIp) {
	}

	private record LoginKey(String normalizedEmail, String remoteIp) {
	}

	private static final class Bucket {

		private final ArrayDeque<Instant> events = new ArrayDeque<>();
		private Instant lastTouched;

		private void purge(Instant now, Duration window) {
			while (!events.isEmpty() && !events.peekFirst().plus(window).isAfter(now)) {
				events.removeFirst();
			}
		}

		private int size() {
			return events.size();
		}

		private Instant oldest() {
			return events.peekFirst();
		}

		private void record(Instant now) {
			events.addLast(now);
			lastTouched = now;
		}
	}

	private final Object monitor = new Object();
	private final Clock clock;
	private final Duration window;
	private final int signupLimit;
	private final int loginLimit;
	private final int loginFailureLimit;
	private final int maxIpKeys;
	private final int maxFailureKeys;
	private final Map<IpKey, Bucket> ipBuckets = new HashMap<>();
	private final Map<LoginKey, Bucket> loginFailureBuckets = new HashMap<>();
	private final Map<LoginKey, GatePermit> activeLoginVerifications = new HashMap<>();

	public InMemoryAuthenticationRequestLimiter(
		AuthenticationRequestProtectionProperties properties, Clock clock) {
		Objects.requireNonNull(properties, "properties").validate();
		this.clock = Objects.requireNonNull(clock, "clock");
		this.window = properties.getWindow();
		this.signupLimit = properties.getSignupLimit();
		this.loginLimit = properties.getLoginLimit();
		this.loginFailureLimit = properties.getLoginFailureLimit();
		this.maxIpKeys = properties.getMaxIpKeys();
		this.maxFailureKeys = properties.getMaxFailureKeys();
	}

	@Override
	public RateLimitDecision checkAndRecordSignup(String remoteIp) {
		return checkAndRecordIp(IpRequestType.SIGNUP, remoteIp, signupLimit);
	}

	@Override
	public RateLimitDecision checkAndRecordLogin(String remoteIp) {
		return checkAndRecordIp(IpRequestType.LOGIN, remoteIp, loginLimit);
	}

	@Override
	public RateLimitDecision checkLoginFailureAllowed(String normalizedEmail, String remoteIp) {
		LoginKey key = loginKey(normalizedEmail, remoteIp);
		Instant now = now();
		synchronized (monitor) {
			purgeFailureBuckets(now);
			Bucket bucket = loginFailureBuckets.get(key);
			if (bucket == null || bucket.size() < loginFailureLimit) {
				return RateLimitDecision.permitted();
			}
			return RateLimitDecision.rejected(retryAfterSeconds(bucket.oldest(), now));
		}
	}

	@Override
	public RateLimitDecision recordLoginFailure(String normalizedEmail, String remoteIp) {
		LoginKey key = loginKey(normalizedEmail, remoteIp);
		Instant now = now();
		synchronized (monitor) {
			purgeFailureBuckets(now);
			Bucket bucket = loginFailureBuckets.get(key);
			if (bucket != null && bucket.size() >= loginFailureLimit) {
				return RateLimitDecision.rejected(retryAfterSeconds(bucket.oldest(), now));
			}
			if (bucket == null) {
				bucket = getOrCreateFailureBucket(key);
			}
			bucket.record(now);
			return RateLimitDecision.permitted();
		}
	}

	@Override
	public void resetLoginFailures(String normalizedEmail, String remoteIp) {
		LoginKey key = loginKey(normalizedEmail, remoteIp);
		synchronized (monitor) {
			loginFailureBuckets.remove(key);
		}
	}

	@Override
	public Optional<LoginVerificationPermit> tryAcquireLoginVerification(
		String normalizedEmail, String remoteIp) {
		LoginKey key = loginKey(normalizedEmail, remoteIp);
		synchronized (monitor) {
			if (activeLoginVerifications.containsKey(key)) {
				return Optional.empty();
			}
			GatePermit permit = new GatePermit(key);
			activeLoginVerifications.put(key, permit);
			return Optional.of(permit);
		}
	}

	/** 현재 제한 상태의 크기를 검증하기 위한 관측값이다. 민감한 키는 반환하지 않는다. */
	public int ipBucketCount() {
		synchronized (monitor) {
			purgeIpBuckets(now());
			return ipBuckets.size();
		}
	}

	/** 현재 제한 상태의 크기를 검증하기 위한 관측값이다. 민감한 키는 반환하지 않는다. */
	public int loginFailureBucketCount() {
		synchronized (monitor) {
			purgeFailureBuckets(now());
			return loginFailureBuckets.size();
		}
	}

	/** 현재 진행 중인 동일 키 검증 게이트 수를 반환한다. 키 자체는 노출하지 않는다. */
	public int activeLoginVerificationCount() {
		synchronized (monitor) {
			return activeLoginVerifications.size();
		}
	}

	private RateLimitDecision checkAndRecordIp(IpRequestType type, String remoteIp, int limit) {
		IpKey key = new IpKey(type, requireKeyPart(remoteIp, "remoteIp"));
		Instant now = now();
		synchronized (monitor) {
			purgeIpBuckets(now);
			Bucket bucket = ipBuckets.get(key);
			if (bucket != null && bucket.size() >= limit) {
				return RateLimitDecision.rejected(retryAfterSeconds(bucket.oldest(), now));
			}
			if (bucket == null) {
				bucket = getOrCreateIpBucket(key);
			}
			bucket.record(now);
			return RateLimitDecision.permitted();
		}
	}

	private Bucket getOrCreateIpBucket(IpKey key) {
		evictIfNeeded(ipBuckets, maxIpKeys);
		Bucket bucket = new Bucket();
		ipBuckets.put(key, bucket);
		return bucket;
	}

	private Bucket getOrCreateFailureBucket(LoginKey key) {
		evictIfNeeded(loginFailureBuckets, maxFailureKeys);
		Bucket bucket = new Bucket();
		loginFailureBuckets.put(key, bucket);
		return bucket;
	}

	private void purgeIpBuckets(Instant now) {
		purgeBuckets(ipBuckets, now);
	}

	private void purgeFailureBuckets(Instant now) {
		purgeBuckets(loginFailureBuckets, now);
	}

	private void purgeBuckets(Map<?, Bucket> buckets, Instant now) {
		Iterator<? extends Map.Entry<?, Bucket>> iterator = buckets.entrySet().iterator();
		while (iterator.hasNext()) {
			Bucket bucket = iterator.next().getValue();
			bucket.purge(now, window);
			if (bucket.size() == 0) {
				iterator.remove();
			}
		}
	}

	private void evictIfNeeded(Map<?, Bucket> buckets, int maxKeys) {
		if (buckets.size() < maxKeys) {
			return;
		}
		Map.Entry<?, Bucket> candidate = buckets.entrySet().stream()
			.min(
				Comparator.comparingInt(
					(Map.Entry<?, Bucket> entry) -> entry.getValue().size())
					.thenComparing(
						entry -> Optional.ofNullable(
							entry.getValue().lastTouched)
							.orElse(Instant.MIN)))
			.orElse(null);
		if (candidate != null) {
			buckets.remove(candidate.getKey());
		}
	}

	private int retryAfterSeconds(Instant oldest, Instant now) {
		if (oldest == null) {
			return 1;
		}
		Instant expiry = oldest.plus(window);
		Duration remaining = Duration.between(now, expiry);
		if (remaining.isNegative() || remaining.isZero()) {
			return 1;
		}
		long seconds = remaining.getSeconds();
		if (remaining.getNano() > 0) {
			seconds++;
		}
		return (int)Math.max(1, Math.min(Integer.MAX_VALUE, seconds));
	}

	private LoginKey loginKey(String normalizedEmail, String remoteIp) {
		return new LoginKey(
			requireKeyPart(normalizedEmail, "normalizedEmail"),
			requireKeyPart(remoteIp, "remoteIp"));
	}

	private String requireKeyPart(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}

	private Instant now() {
		return Instant.now(clock);
	}

	private final class GatePermit implements LoginVerificationPermit {

		private final LoginKey key;
		private final AtomicBoolean released = new AtomicBoolean();

		private GatePermit(LoginKey key) {
			this.key = key;
		}

		@Override
		public void close() {
			if (!released.compareAndSet(false, true)) {
				return;
			}
			synchronized (monitor) {
				if (activeLoginVerifications.get(key) == this) {
					activeLoginVerifications.remove(key);
				}
			}
		}
	}
}
