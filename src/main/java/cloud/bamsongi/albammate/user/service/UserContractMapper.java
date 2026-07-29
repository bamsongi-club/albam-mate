package cloud.bamsongi.albammate.user.service;

import cloud.bamsongi.albammate.user.contract.UserAccount;
import cloud.bamsongi.albammate.user.contract.UserCredentials;
import cloud.bamsongi.albammate.user.contract.UserProfile;
import cloud.bamsongi.albammate.user.entity.User;

/** 사용자 엔티티를 사용자 모듈의 공개 계약으로 변환한다. */
final class UserContractMapper {

	private UserContractMapper() {}

	static UserAccount toUserAccount(User user) {
		return new UserAccount(user.getId(), user.getNickname());
	}

	static UserCredentials toUserCredentials(User user) {
		return new UserCredentials(user.getId(), user.getNickname(), user.getPasswordHash());
	}

	static UserProfile toUserProfile(User user) {
		return new UserProfile(user.getId(), user.getNickname());
	}
}
