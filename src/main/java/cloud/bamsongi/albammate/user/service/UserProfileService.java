package cloud.bamsongi.albammate.user.service;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

	@Transactional
	public UserProfileResponse changeProfileImage(long userId, String imageUrl) {
		User user = requireCurrentUser(userId);
		String previousUrl = user.getProfileImageUrl();
		user.changeProfileImageUrl(imageUrl);
		if (previousUrl != null && !previousUrl.equals(imageUrl)) {
			profileImageStorage.delete(previousUrl);
		}
		return UserProfileResponse.from(user);
	}

	@Transactional
	public UserProfileResponse removeProfileImage(long userId) {
		User user = requireCurrentUser(userId);
		String previousUrl = user.getProfileImageUrl();
		user.changeProfileImageUrl(null);
		if (previousUrl != null) {
			profileImageStorage.delete(previousUrl);
		}
		return UserProfileResponse.from(user);
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
}
