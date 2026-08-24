package cloud.bamsongi.albammate.matching.service.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.response.PageResponse;
import cloud.bamsongi.albammate.matching.dto.MatchBlockListItemResponse;
import cloud.bamsongi.albammate.matching.entity.MatchBlock;
import cloud.bamsongi.albammate.matching.repository.MatchBlockRepository;
import cloud.bamsongi.albammate.user.contract.UserPublicProfile;
import cloud.bamsongi.albammate.user.contract.UserQuery;

/** 현재 사용자가 만든 차단 관계만 공개 프로필과 함께 조회한다. */
@Service
public class MatchBlockQueryService {

	private final MatchBlockRepository matchBlockRepository;
	private final UserQuery userQuery;

	public MatchBlockQueryService(MatchBlockRepository matchBlockRepository, UserQuery userQuery) {
		this.matchBlockRepository = Objects.requireNonNull(matchBlockRepository, "matchBlockRepository");
		this.userQuery = Objects.requireNonNull(userQuery, "userQuery");
	}

	@Transactional(readOnly = true)
	public PageResponse<MatchBlockListItemResponse> findPage(long blockerUserId, int page, int size) {
		PageRequest pageable = PageRequest.of(page, size);
		Page<MatchBlock> blockPage = matchBlockRepository.findByBlockerUserIdOrderByCreatedAtDescIdDesc(
			blockerUserId, pageable);
		List<Long> blockedUserIds = blockPage.getContent().stream()
			.map(MatchBlock::getBlockedUserId)
			.toList();
		Map<Long, UserPublicProfile> profilesByUserId = blockedUserIds.isEmpty()
			? Map.of()
			: userQuery.findPublicProfilesByIds(blockedUserIds);

		List<MatchBlockListItemResponse> items = new ArrayList<>();
		for (MatchBlock block : blockPage.getContent()) {
			UserPublicProfile profile = Objects.requireNonNull(
				profilesByUserId.get(block.getBlockedUserId()), "blocked user public profile");
			items.add(MatchBlockListItemResponse.from(block, profile));
		}
		return PageResponse.from(new PageImpl<>(items, pageable, blockPage.getTotalElements()));
	}
}
