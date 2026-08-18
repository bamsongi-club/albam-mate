package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.matching.entity.MatchBlock;
import cloud.bamsongi.albammate.matching.repository.MatchBlockRepository;
import cloud.bamsongi.albammate.matching.service.query.MatchBlockQueryService;
import cloud.bamsongi.albammate.user.contract.UserPublicProfile;
import cloud.bamsongi.albammate.user.contract.UserQuery;

class MatchBlockQueryServiceTest {

	@Test
	void 한_페이지의_공개_프로필은_일괄_조회_한번으로_조립한다() {
		MatchBlockRepository matchBlockRepository = mock(MatchBlockRepository.class);
		UserQuery userQuery = mock(UserQuery.class);
		MatchBlockQueryService service = new MatchBlockQueryService(matchBlockRepository, userQuery);
		MatchBlock firstBlock = block(10L, 1L, 2L);
		MatchBlock secondBlock = block(11L, 1L, 3L);
		PageRequest pageRequest = PageRequest.of(0, 10);
		when(matchBlockRepository.findByBlockerUserIdOrderByCreatedAtDescIdDesc(1L, pageRequest))
			.thenReturn(new PageImpl<>(List.of(firstBlock, secondBlock), pageRequest, 2));
		when(userQuery.findPublicProfilesByIds(List.of(2L, 3L))).thenReturn(Map.of(
			2L, new UserPublicProfile(2L, "첫대상", "https://cdn.example.com/first.png"),
			3L, new UserPublicProfile(3L, "둘대상", "https://cdn.example.com/second.png")));

		var result = service.findPage(1L, 0, 10);

		assertEquals(2, result.content().size());
		assertEquals("첫대상", result.content().getFirst().blockedUser().nickname());
		verify(userQuery).findPublicProfilesByIds(List.of(2L, 3L));
	}

	@Test
	void 빈_페이지에서는_공개_프로필을_조회하지_않는다() {
		MatchBlockRepository matchBlockRepository = mock(MatchBlockRepository.class);
		UserQuery userQuery = mock(UserQuery.class);
		MatchBlockQueryService service = new MatchBlockQueryService(matchBlockRepository, userQuery);
		PageRequest pageRequest = PageRequest.of(1, 10);
		when(matchBlockRepository.findByBlockerUserIdOrderByCreatedAtDescIdDesc(1L, pageRequest))
			.thenReturn(new PageImpl<>(List.of(), pageRequest, 1));

		var result = service.findPage(1L, 1, 10);

		assertEquals(0, result.content().size());
		verifyNoInteractions(userQuery);
	}

	private MatchBlock block(long blockId, long blockerUserId, long blockedUserId) {
		MatchBlock block = MatchBlock.create(blockerUserId, blockedUserId, Instant.parse("2026-08-19T00:00:00Z"));
		ReflectionTestUtils.setField(block, "id", blockId);
		return block;
	}
}
