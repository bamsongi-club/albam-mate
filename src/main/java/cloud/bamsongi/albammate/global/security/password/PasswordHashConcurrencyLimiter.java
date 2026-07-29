package cloud.bamsongi.albammate.global.security.password;

import java.util.Optional;

/** 애플리케이션 인스턴스의 동시 비밀번호 해시 작업 슬롯 계약이다. */
public interface PasswordHashConcurrencyLimiter {

	Optional<PasswordHashPermit> tryAcquire();

	int maxConcurrent();

	int currentConcurrent();
}
