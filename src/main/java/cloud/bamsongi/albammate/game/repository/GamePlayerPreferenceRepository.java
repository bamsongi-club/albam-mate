package cloud.bamsongi.albammate.game.repository;

import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.bamsongi.albammate.game.entity.*;

public interface GamePlayerPreferenceRepository extends JpaRepository<GamePlayerPreference, GamePlayerPreferenceId> {
	List<GamePlayerPreference> findByGameIdOrderByIdPlayerCountAsc(Long gameId);
}
