package cloud.bamsongi.albammate.matching.dto;

import cloud.bamsongi.albammate.matching.MatchProposalResponseAction;
import jakarta.validation.constraints.NotNull;

public record MatchProposalResponseRequest(@NotNull MatchProposalResponseAction action) {
}
