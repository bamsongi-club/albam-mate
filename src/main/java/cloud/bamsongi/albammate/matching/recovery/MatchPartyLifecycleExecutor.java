package cloud.bamsongi.albammate.matching.recovery;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.matching.MatchPartyStatus;
import cloud.bamsongi.albammate.matching.contract.MatchChatSystemMessagePort;
import cloud.bamsongi.albammate.matching.entity.MatchParty;
import cloud.bamsongi.albammate.matching.repository.MatchPartyRepository;

@Service
public class MatchPartyLifecycleExecutor {

	private final MatchPartyRepository partyRepository;
	private final MatchChatSystemMessagePort chatSystemMessagePort;
	private final JdbcTemplate jdbcTemplate;

	public MatchPartyLifecycleExecutor(
		MatchPartyRepository partyRepository,
		MatchChatSystemMessagePort chatSystemMessagePort,
		JdbcTemplate jdbcTemplate) {
		this.partyRepository = partyRepository;
		this.chatSystemMessagePort = chatSystemMessagePort;
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recover(long partyId) {
		MatchParty party = partyRepository.findByIdForUpdate(partyId).orElse(null);
		if (party == null) {
			return;
		}
		Timestamp operationTimestamp = jdbcTemplate.queryForObject("select clock_timestamp()", Timestamp.class);
		Instant operationTime = operationTimestamp.toInstant();
		if (party.getStatus() != MatchPartyStatus.ACTIVE) {
			return;
		}
		recoverActiveParty(party, operationTime);
	}

	private void recoverActiveParty(MatchParty party, Instant operationTime) {
		if (party.isClosingDue(operationTime)) {
			party.close(operationTime);
			return;
		}
		if (party.isCloseNoticeDue(operationTime)
			&& !chatSystemMessagePort.hasPersistedEvent(party.getId(), "CLOSES_IN_ONE_HOUR")) {
			chatSystemMessagePort.record(party.getId(), "CLOSES_IN_ONE_HOUR");
		}
	}

}
