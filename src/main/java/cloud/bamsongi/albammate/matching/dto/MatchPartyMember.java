package cloud.bamsongi.albammate.matching.dto;

import java.util.UUID;

public record MatchPartyMember(UUID participantRef, String nickname, String profileImageUrl, boolean isMine) {
}
