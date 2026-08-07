package cloud.bamsongi.albammate.user.service;

import java.io.InputStream;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import cloud.bamsongi.albammate.global.exception.UnauthenticatedException;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import cloud.bamsongi.albammate.user.dto.UserProfileResponse;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 현재 인증 사용자의 프로필 조회와 닉네임 변경을 사용자 모듈 트랜잭션으로 처리한다. */
@Service
@RequiredArgsConstructor
public class UserProfileService {

	@NonNull private final UserRepository userRepository;
	@NonNull private final ProfileImageStorage profileImageStorage;

	@Transactional(readOnly = true)
	public UserProfileResponse findProfile(long userId) {
		return UserProfileResponse.from(requireCurrentUser(userId));
	}

	@Transactional
	public UserProfileResponse changeNickname(long userId, UserNickname nickname) {
		Objects.requireNonNull(nickname, "nickname");
		User user = requireCurrentUser(userId);
		user.changeNickname(nickname.value());
		return UserProfileResponse.from(user);
	}

	/**
	 * 새 프로필 이미지를 저장하고 사용자 행에 반영하는 전체 흐름(파일 저장, DB 갱신, 실패 시 새 파일 보상 삭제,
	 * 이전 파일의 커밋 후 삭제)을 이 서비스 하나가 책임진다. HTTP 계층은 파일을 어떻게, 어디에 저장할지 몰라야 한다.
	 * 사용자 행을 {@link UserRepository#findByIdForUpdate}로 잠가 같은 사용자에 대한 동시 변경을 직렬화하므로,
	 * previousUrl은 항상 마지막으로 커밋된 파일을 가리키고 그 파일만 삭제 대상이 된다.
	 */
	@Transactional
	public UserProfileResponse uploadProfileImage(
		long userId, InputStream inputStream, String originalFilename, String contentType) {
		User user = requireCurrentUserForUpdate(userId);
		String previousUrl = user.getProfileImageUrl();
		String newUrl = profileImageStorage.store(userId, inputStream, originalFilename, contentType);
		try {
			user.changeProfileImageUrl(newUrl);
		} catch (RuntimeException exception) {
			profileImageStorage.delete(newUrl);
			throw exception;
		}
		if (previousUrl != null && !previousUrl.equals(newUrl)) {
			deleteAfterCommit(previousUrl);
		}
		return UserProfileResponse.from(user);
	}

	@Transactional
	public UserProfileResponse removeProfileImage(long userId) {
		User user = requireCurrentUserForUpdate(userId);
		String previousUrl = user.getProfileImageUrl();
		user.changeProfileImageUrl(null);
		if (previousUrl != null) {
			deleteAfterCommit(previousUrl);
		}
		return UserProfileResponse.from(user);
	}

	/**
	 * 이전 파일은 DB 커밋이 실제로 성공한 뒤에만 지운다. 커밋 전에 지우면 커밋 실패 시 DB는 이전 URL로 남는데
	 * 파일은 이미 사라져 프로필이 깨진다.
	 */
	private void deleteAfterCommit(String previousUrl) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			profileImageStorage.delete(previousUrl);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				profileImageStorage.delete(previousUrl);
			}
		});
	}

	/**
	 * 세션이 가리키는 사용자를 불러오고, ID가 비정상이거나 그 사용자가 더 이상 없으면 미인증으로 변환한다.
	 *
	 * <p>사용자를 찾지 못한 경우를 404가 아니라 401로 다룬다. 여기의 {@code userId}는 요청 경로 변수가 아니라 서버 세션에서
	 * 나오므로, 행이 없다는 것은 "없는 리소스를 조회했다"가 아니라 "세션이 가리키는 계정이 사라졌다"는 뜻이다. 클라이언트가 할
	 * 일은 다시 로그인하는 것이다.
	 */
	private User requireCurrentUser(long userId) {
		if (userId <= 0) {
			throw new UnauthenticatedException();
		}
		return userRepository.findById(userId).orElseThrow(UnauthenticatedException::new);
	}

	/** {@link #requireCurrentUser}와 같지만, 프로필 이미지 교체처럼 동시 갱신을 직렬화해야 하는 흐름에 쓴다. */
	private User requireCurrentUserForUpdate(long userId) {
		if (userId <= 0) {
			throw new UnauthenticatedException();
		}
		return userRepository.findByIdForUpdate(userId).orElseThrow(UnauthenticatedException::new);
	}
}
