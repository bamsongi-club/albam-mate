package cloud.bamsongi.albammate.assistant.dto;

import cloud.bamsongi.albammate.assistant.entity.AssistantDraft;

public record AssistantDraftResponse(long draftId, long draftVersion, String status, Input input, Result result) {
	public static AssistantDraftResponse from(AssistantDraft draft) {
		Result result = draft.getRoomId() == null ? null : new Result(draft.getRoomId(), draft.getChatRoomId());
		return new AssistantDraftResponse(draft.getId(), draft.getDraftVersion(), draft.getStatus().name(),
			new Input(draft.getRoomType(), draft.getTitle(), draft.getDescription(), draft.getGameId(),
				draft.getExperienceLevel(), draft.isRulemasterLed(), draft.getStartAt(), draft.getRegion(),
				draft.getPlace(), draft.getCapacity()),
			result);
	}

	public record Input(String roomType, String title, String description, Long gameId, String experienceLevel,
		boolean isRulemasterLed, java.time.Instant startsAt, String region, String place, int recruitmentCapacity) {
	}
	public record Result(long roomId, long chatRoomId) {
	}
}
