package cloud.bamsongi.albammate.matching.dto;

import java.time.Instant;

import cloud.bamsongi.albammate.matching.entity.MatchBlock;
import cloud.bamsongi.albammate.user.contract.UserPublicProfile;

/** 차단 목록과 차단 성공 응답에 노출하는 최소 공개 정보다. */
public record MatchBlockListItemResponse(long blockId, BlockedUser blockedUser, Instant blockedAt) {

	public static MatchBlockListItemResponse from(MatchBlock block, UserPublicProfile profile) {
		return new MatchBlockListItemResponse(
			block.getId(), new BlockedUser(profile.nickname(), profile.profileImageUrl()), block.getCreatedAt());
	}

	/** 차단 관계에서 노출하는 대상 사용자의 공개 프로필이다. */
	public record BlockedUser(String nickname, String profileImageUrl) {
	}
}
