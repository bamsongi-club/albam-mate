package cloud.bamsongi.albammate.game.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.bamsongi.albammate.game.entity.GamePlayerPreference;
import cloud.bamsongi.albammate.game.entity.GamePlayerPreferenceId;

public interface GamePlayerPreferenceRepository extends JpaRepository<GamePlayerPreference, GamePlayerPreferenceId> {
	List<GamePlayerPreference> findByGameIdOrderByIdPlayerCountAsc(Long gameId);
}
