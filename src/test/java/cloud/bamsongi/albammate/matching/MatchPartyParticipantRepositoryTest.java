package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.fixture.GameFixture;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.global.config.JpaConfig;
import cloud.bamsongi.albammate.global.config.TimeConfig;
import cloud.bamsongi.albammate.matching.entity.MatchParty;
import cloud.bamsongi.albammate.matching.entity.MatchPartyParticipant;
import cloud.bamsongi.albammate.matching.repository.MatchPartyParticipantRepository;
import cloud.bamsongi.albammate.matching.repository.MatchPartyRepository;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;

@DataJpaTest(properties = {
	"spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import({JpaConfig.class, TimeConfig.class})
class MatchPartyParticipantRepositoryTest {

	private static final Instant FIXED_TIME = Instant.parse("2026-08-18T00:00:00Z");

	@Autowired
	private MatchPartyParticipantRepository participantRepository;
	@Autowired
	private MatchPartyRepository partyRepository;
	@Autowired
	private GameRepository gameRepository;
	@Autowired
	private UserRepository userRepository;

	@Test
	void 참가자_ref는_같은_Party에서만_해석되고_이탈과_CLOSED_보존_멤버십은_조회된다() {
		User user = saveUser("member");
		User formerUser = saveUser("former-member");
		Game game = gameRepository.saveAndFlush(GameFixture.valid(3_001L, "Participant Game"));
		MatchParty activeParty = saveParty(game.getId(), MatchPartyStatus.ACTIVE);
		MatchParty otherParty = saveParty(game.getId(), MatchPartyStatus.ACTIVE);
		MatchParty closedParty = saveParty(game.getId(), MatchPartyStatus.CLOSED);
		UUID activeParticipantRef = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID leftParticipantRef = UUID.fromString("00000000-0000-0000-0000-000000000002");
		UUID closedParticipantRef = UUID.fromString("00000000-0000-0000-0000-000000000003");
		UUID otherParticipantRef = UUID.fromString("00000000-0000-0000-0000-000000000004");

		participantRepository.saveAndFlush(
			MatchPartyParticipant.create(activeParty.getId(), user.getId(), activeParticipantRef, FIXED_TIME));
		participantRepository.saveAndFlush(
			MatchPartyParticipant.create(otherParty.getId(), user.getId(), otherParticipantRef, FIXED_TIME));
		MatchPartyParticipant formerParticipant = MatchPartyParticipant.create(
			activeParty.getId(), formerUser.getId(), leftParticipantRef, FIXED_TIME);
		ReflectionTestUtils.setField(formerParticipant, "leftAt", FIXED_TIME.plusSeconds(60));
		participantRepository.saveAndFlush(formerParticipant);
		participantRepository.saveAndFlush(
			MatchPartyParticipant.create(closedParty.getId(), user.getId(), closedParticipantRef, FIXED_TIME));

		assertTrue(
			participantRepository.findByPartyIdAndParticipantRef(activeParty.getId(), activeParticipantRef)
				.isPresent());
		assertFalse(
			participantRepository.findByPartyIdAndParticipantRef(otherParty.getId(), activeParticipantRef).isPresent());
		assertTrue(
			participantRepository.findParticipantByPartyIdAndUserId(activeParty.getId(), formerUser.getId())
				.isPresent());
		assertTrue(
			participantRepository.findParticipantByPartyIdAndUserId(closedParty.getId(), user.getId()).isPresent());
	}

	private User saveUser(String role) {
		return userRepository.saveAndFlush(
			User.create("match-participant-" + role + "@example.com", "{bcrypt}hash", "매칭 " + role));
	}

	private MatchParty saveParty(long gameId, MatchPartyStatus status) {
		MatchParty party = BeanUtils.instantiateClass(MatchParty.class);
		ReflectionTestUtils.setField(party, "gameId", gameId);
		ReflectionTestUtils.setField(party, "status", status);
		ReflectionTestUtils.setField(party, "preparingStartedAt", FIXED_TIME);
		if (status == MatchPartyStatus.ACTIVE) {
			ReflectionTestUtils.setField(party, "chatOpenedAt", FIXED_TIME.plusSeconds(60));
			ReflectionTestUtils.setField(party, "closesAt", FIXED_TIME.plusSeconds(3_600));
		}
		if (status == MatchPartyStatus.CLOSED) {
			ReflectionTestUtils.setField(party, "closedAt", FIXED_TIME.plusSeconds(3_600));
			ReflectionTestUtils.setField(party, "purgeAfter", FIXED_TIME.plusSeconds(604_800));
		}
		return partyRepository.saveAndFlush(party);
	}
}
