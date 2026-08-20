package cloud.bamsongi.albammate.matching.recovery;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.matching.MatchPartyStatus;
import cloud.bamsongi.albammate.matching.contract.MatchChatCleanupPort;
import cloud.bamsongi.albammate.matching.repository.MatchPartyRepository;

@Service
public class MatchClosedPartyCleanupExecutor {

	private final MatchPartyRepository partyRepository;
	private final MatchChatCleanupPort chatCleanupPort;
	private final JdbcTemplate jdbcTemplate;

	public MatchClosedPartyCleanupExecutor(
		MatchPartyRepository partyRepository,
		MatchChatCleanupPort chatCleanupPort,
		JdbcTemplate jdbcTemplate) {
		this.partyRepository = partyRepository;
		this.chatCleanupPort = chatCleanupPort;
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void cleanUp(long partyId) {
		var party = partyRepository.findByIdForUpdate(partyId).orElse(null);
		if (party == null || party.getStatus() != MatchPartyStatus.CLOSED) {
			return;
		}
		Instant operationTime = jdbcTemplate.queryForObject("select current_timestamp", Timestamp.class).toInstant();
		if (!party.isPurgeDue(operationTime)) {
			return;
		}
		chatCleanupPort.cleanup(party.getId());
		partyRepository.delete(party);
	}
}
