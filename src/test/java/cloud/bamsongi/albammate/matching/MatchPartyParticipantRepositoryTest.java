package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

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
	private UserRepository userRepository;

	@Test
	void 참가자_ref는_같은_Party에서만_해석되고_이탈과_CLOSED_보존_멤버십은_조회된다() {
		User user = saveUser("member");
		User formerUser = saveUser("former-member");
		MatchParty activeParty = saveParty(MatchPartyStatus.ACTIVE);
		MatchParty otherParty = saveParty(MatchPartyStatus.ACTIVE);
		MatchParty closedParty = saveParty(MatchPartyStatus.CLOSED);
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

	@Test
	void 배치_조회는_같은_Party의_현재와_과거_참가자만_반환하고_다른_Party나_없는_사용자는_제외한다() {
		User user = saveUser("batch-member");
		User formerUser = saveUser("batch-former-member");
		User otherPartyUser = saveUser("batch-other-party-member");
		MatchParty party = saveParty(MatchPartyStatus.ACTIVE);
		MatchParty otherParty = saveParty(MatchPartyStatus.ACTIVE);
		UUID participantRef = UUID.fromString("00000000-0000-0000-0000-000000000101");
		UUID formerParticipantRef = UUID.fromString("00000000-0000-0000-0000-000000000102");
		UUID otherPartyParticipantRef = UUID.fromString("00000000-0000-0000-0000-000000000103");

		participantRepository.saveAndFlush(
			MatchPartyParticipant.create(party.getId(), user.getId(), participantRef, FIXED_TIME));
		MatchPartyParticipant formerParticipant = MatchPartyParticipant.create(
			party.getId(), formerUser.getId(), formerParticipantRef, FIXED_TIME);
		ReflectionTestUtils.setField(formerParticipant, "leftAt", FIXED_TIME.plusSeconds(60));
		participantRepository.saveAndFlush(formerParticipant);
		participantRepository.saveAndFlush(
			MatchPartyParticipant.create(
				otherParty.getId(), otherPartyUser.getId(), otherPartyParticipantRef, FIXED_TIME));

		List<MatchPartyParticipant> result = participantRepository.findParticipantsByPartyIdAndUserIds(
			party.getId(),
			List.of(user.getId(), formerUser.getId(), otherPartyUser.getId(), 999_999L));

		Map<Long, UUID> refsByUserId = result.stream()
			.collect(Collectors.toMap(
				participant -> participant.getId().getUserId(),
				MatchPartyParticipant::getParticipantRef));
		assertEquals(2, result.size());
		assertEquals(participantRef, refsByUserId.get(user.getId()));
		assertEquals(formerParticipantRef, refsByUserId.get(formerUser.getId()));
		assertFalse(refsByUserId.containsKey(otherPartyUser.getId()));
		assertFalse(refsByUserId.containsKey(999_999L));
	}

	private User saveUser(String role) {
		return userRepository.saveAndFlush(
			User.create("match-participant-" + role + "@example.com", "{bcrypt}hash", "매칭 " + role));
	}

	private MatchParty saveParty(MatchPartyStatus status) {
		MatchParty party = BeanUtils.instantiateClass(MatchParty.class);
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
