package cloud.bamsongi.albammate.user.dto;

import cloud.bamsongi.albammate.user.entity.User;

/** 프로필 API가 공개하는 현재 사용자의 최소 정보다. */
public record UserProfileResponse(Long id, String nickname, String profileImageUrl) {

	public static UserProfileResponse from(User user) {
		return new UserProfileResponse(user.getId(), user.getNickname(), user.getProfileImageUrl());
	}
}
