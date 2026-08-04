package cloud.bamsongi.albammate.game.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.bamsongi.albammate.game.entity.GameMechanismRelation;
import cloud.bamsongi.albammate.game.entity.GameMechanismRelationId;

public interface GameMechanismRelationRepository
	extends JpaRepository<GameMechanismRelation, GameMechanismRelationId> {}
