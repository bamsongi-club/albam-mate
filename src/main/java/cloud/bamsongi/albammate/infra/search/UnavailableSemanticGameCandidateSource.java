package cloud.bamsongi.albammate.infra.search;

import java.util.List;

import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.game.contract.DenseCandidateSource;
import cloud.bamsongi.albammate.game.contract.SemanticSearchUnavailableException;

/** 실제 승인 index가 배포되기 전에는 semantic 후보를 fail-closed로 막는다. */
@Component
public class UnavailableSemanticGameCandidateSource implements DenseCandidateSource {

	@Override
	public List<Candidate> findCandidates(String rawQuery) {
		throw new SemanticSearchUnavailableException();
	}
}
