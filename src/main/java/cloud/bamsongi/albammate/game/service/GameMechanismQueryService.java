package cloud.bamsongi.albammate.game.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.dto.GameMechanismOption;
import cloud.bamsongi.albammate.game.repository.GameMechanismRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 검수 후 공개된 게임 메커니즘 선택지를 조회한다. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GameMechanismQueryService {

	@NonNull private final GameMechanismRepository gameMechanismRepository;

	public List<GameMechanismOption> findPublicOptions() {
		return gameMechanismRepository.findPublicOptions().stream()
			.map(GameMechanismOption::from)
			.toList();
	}
}
