package cloud.bamsongi.albammate.user.service;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.security.password.PasswordHashExecutor;
import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.UserAccount;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserCredentials;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.exception.EmailAlreadyExistsException;
import cloud.bamsongi.albammate.user.repository.UserRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 사용자 계정 생성과 자격증명 저장을 담당하는 사용자 모듈의 애플리케이션 구현이다. */
@Service
@RequiredArgsConstructor
public class UserAccountApplicationService implements UserAccountService {

	@NonNull private final UserRepository userRepository;
	@NonNull private final PasswordEncoder passwordEncoder;
	@NonNull private final PasswordHashExecutor passwordHashExecutor;

	/**
	 * 해시 실행 슬롯을 먼저 얻고, 그 안에서 이메일 중복 판정·해시·사용자 생성을 수행한다.
	 *
	 * <p>슬롯 획득이 중복 조회보다 앞서는 것은 ADR-0013의 계약이다. 슬롯이 없으면 해시도 사용자 생성도 시작하지 않으므로,
	 * 과부하 상황에서 반복 가입 시도가 DB까지 도달하지 않는다. 주목적은 CPU·메모리 보호이고 DB 보호는 이 순서가 함께
	 * 보장하는 효과다. 슬롯 안에 DB 왕복이 들어가는 것은 그래서 의도된 비용이다.
	 *
	 * <p>중복을 먼저 확인해도 동시 요청은 통과할 수 있으므로, DB unique 제약 위반도 같은 오류로 변환한다.
	 */
	@Override
	@Transactional
	public UserAccount createAccount(CreateUserAccountCommand command) {
		String normalizedEmail = command.email().value();
		String normalizedNickname = command.nickname().value();

		return passwordHashExecutor.execute(
			() -> {
				if (userRepository.existsByEmail(normalizedEmail)) {
					throw new EmailAlreadyExistsException();
				}

				String passwordHash = passwordEncoder.encode(command.rawPassword().value());
				User user = User.create(normalizedEmail, passwordHash, normalizedNickname);
				try {
					User saved = userRepository.saveAndFlush(user);
					return UserContractMapper.toUserAccount(saved);
				} catch (DataIntegrityViolationException exception) {
					throw new EmailAlreadyExistsException(exception);
				}
			});
	}

	/** 이메일로 로그인 검증에 필요한 ID·닉네임·저장 해시만 조회한다. */
	@Override
	@Transactional(readOnly = true)
	public Optional<UserCredentials> findCredentialsByEmail(UserEmail email) {
		return userRepository
			.findByEmail(email.value())
			.map(UserContractMapper::toUserCredentials);
	}

	/** 로그인 성공 뒤 현재 비밀번호 비용으로 저장 해시를 갱신한다. */
	@Override
	@Transactional
	public void updatePasswordHash(Long userId, String passwordHash) {
		if (userId == null || userId <= 0) {
			throw new IllegalArgumentException("userId must be positive");
		}
		requireValue(passwordHash, "passwordHash");
		User user = userRepository
			.findById(userId)
			.orElseThrow(
				() -> new IllegalStateException(
					"user credential no longer exists"));
		user.changePasswordHash(passwordHash);
	}

	private void requireValue(String value, String name) {
		if (value == null || value.isEmpty()) {
			throw new IllegalArgumentException(name + " must not be empty");
		}
	}
}
