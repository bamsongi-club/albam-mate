package cloud.bamsongi.albammate.game.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import cloud.bamsongi.albammate.game.contract.UpcomingRoomCountQuery;
import cloud.bamsongi.albammate.game.dto.GameListItem;
import cloud.bamsongi.albammate.game.repository.GameListRow;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GameListQueryService {

	private final GameRepository gameRepository;
	private final Clock clock;
	private final UpcomingRoomCountQuery upcomingRoomCountQuery;

	/**
	 * 게임 이름 검색 결과를 페이지로 조회하고 조회 시각 기준 예정 모임 수를 결합한다.
	 *
	 * <p>{@code keyword}가 {@code null}이거나 공백이면 전체 게임을 조회한다.
	 *
	 * @param keyword 게임 이름 검색어
	 * @param pageable 페이지와 정렬 조건
	 * @return 예정 모임 수가 포함된 게임 목록 페이지
	 */
	public Page<GameListItem> findPage(String keyword, Pageable pageable) {
		return findPage(keyword, false, pageable);
	}

	/**
	 * 게임 이름 검색과 예정 모임 존재 여부를 적용해 게임 목록을 페이지로 조회한다.
	 *
	 * <p>{@code upcomingOnly}가 참이면 전체 예정 모임 집계를 먼저 조회해 해당 게임만 페이징한다.
	 *
	 * @param keyword 게임 이름 검색어
	 * @param upcomingOnly 예정 모임이 있는 게임만 조회할지 여부
	 * @param pageable 페이지와 정렬 조건
	 * @return 예정 모임 수가 포함된 게임 목록 페이지
	 */
	public Page<GameListItem> findPage(String keyword, boolean upcomingOnly, Pageable pageable) {
		String normalizedKeyword = keyword == null ? null : keyword.strip();
		if (upcomingOnly) {
			return findUpcomingOnlyPage(normalizedKeyword, pageable);
		}

		Page<GameListRow> games = StringUtils.hasText(normalizedKeyword)
			? gameRepository.findListRowsByNameContainingIgnoreCase(
				normalizedKeyword, pageable)
			: gameRepository.findAllListRows(pageable);
		if (games.isEmpty()) {
			return games.map(game -> GameListItem.from(game, 0L));
		}

		Map<Long, Long> upcomingRoomCounts = upcomingRoomCountQuery.findUpcomingRoomCounts(
			games.getContent().stream().map(GameListRow::id).toList(),
			Instant.now(clock));

		return games.map(
			game -> GameListItem.from(game, upcomingRoomCounts.getOrDefault(game.id(), 0L)));
	}

	private Page<GameListItem> findUpcomingOnlyPage(String normalizedKeyword, Pageable pageable) {
		Instant now = Instant.now(clock);
		Map<Long, Long> upcomingRoomCounts = upcomingRoomCountQuery.findUpcomingRoomCounts(now);
		if (upcomingRoomCounts.isEmpty()) {
			return Page.empty(pageable);
		}

		Page<GameListRow> games = StringUtils.hasText(normalizedKeyword)
			? gameRepository.findListRowsByIdInAndNameContainingIgnoreCase(
				upcomingRoomCounts.keySet(), normalizedKeyword, pageable)
			: gameRepository.findListRowsByIdIn(upcomingRoomCounts.keySet(), pageable);
		return games.map(
			game -> GameListItem.from(game, upcomingRoomCounts.getOrDefault(game.id(), 0L)));
	}
}
