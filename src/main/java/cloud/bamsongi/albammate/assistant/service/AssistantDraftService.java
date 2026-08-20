package cloud.bamsongi.albammate.assistant.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.assistant.contract.AssistantConsentGate;
import cloud.bamsongi.albammate.assistant.contract.AssistantConsentRevokedEvent;
import cloud.bamsongi.albammate.assistant.dto.AssistantDraftCreateRequest;
import cloud.bamsongi.albammate.assistant.dto.AssistantDraftPatchRequest;
import cloud.bamsongi.albammate.assistant.dto.AssistantDraftResponse;
import cloud.bamsongi.albammate.assistant.entity.AssistantDraft;
import cloud.bamsongi.albammate.assistant.entity.AssistantDraftStatus;
import cloud.bamsongi.albammate.assistant.entity.AssistantIdempotencyRecord;
import cloud.bamsongi.albammate.assistant.repository.AssistantDraftRepository;
import cloud.bamsongi.albammate.assistant.repository.AssistantIdempotencyRecordRepository;
import cloud.bamsongi.albammate.game.contract.GameQuery;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.contract.AssistantRoomCommand;
import cloud.bamsongi.albammate.room.contract.AssistantRoomCreationCommand;
import cloud.bamsongi.albammate.room.contract.AssistantRoomCreationResult;
import cloud.bamsongi.albammate.user.contract.UserRowLockPort;
import lombok.RequiredArgsConstructor;

/** AI-03 초안의 소유권, 수명과 확인형 Room handoff를 조정한다. */
@Service
@RequiredArgsConstructor
public class AssistantDraftService {

	private final AssistantDraftRepository draftRepository;
	private final AssistantIdempotencyRecordRepository idempotencyRecordRepository;
	private final AssistantConsentGate consentGate;
	private final AssistantConsentProperties properties;
	private final UserRowLockPort userRowLockPort;
	private final AssistantRoomCommand assistantRoomCommand;
	private final GameQuery gameQuery;
	private final Clock clock;

	@Transactional
	public AssistantDraftResponse create(long userId, AssistantDraftCreateRequest request) {
		requireEnabled();
		consentGate.requireGranted(userId);
		Instant now = clock.instant();
		lockUser(userId);
		draftRepository.findActiveByUserIdForUpdate(userId).forEach(AssistantDraft::discard);
		draftRepository.flush();
		idempotencyRecordRepository.deleteByUserIdAndExpiresAtLessThanEqual(userId, now);
		String roomType = requireRoomType(request);
		Long gameId = request.gameId() == null ? null : requireGame(request.gameId());
		if ("GAME_FOCUSED".equals(roomType) && gameId == null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		AssistantDraft draft = AssistantDraft.create(userId, roomType, requireTitle(request),
			request.description() == null ? null : requireDescription(request.description()), gameId,
			requireExperienceLevel(request), requireRulemasterLed(request),
			normalizeRegion(request.region()), requireCapacity(request), requireStartAt(request, now),
			normalizePlace(request.place()), now);
		return AssistantDraftResponse.from(draftRepository.saveAndFlush(draft));
	}

	@Transactional(readOnly = true)
	public AssistantDraftResponse get(long userId, long draftId) {
		AssistantDraft draft = owned(draftId, userId);
		if (draft.isExpiredAt(clock.instant())) {
			throw new BusinessException(ErrorCode.ASSISTANT_DRAFT_EXPIRED);
		}
		return AssistantDraftResponse.from(draft);
	}

	@Transactional
	public ConfirmOutcome confirm(long userId, long draftId, long draftVersion, String idempotencyKey) {
		requireEnabled();
		if (idempotencyKey == null || !idempotencyKey.matches("[\\x21-\\x7e]{1,100}")) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		Instant now = clock.instant();
		lockUser(userId);
		AssistantDraft draft = ownedForUpdate(draftId, userId);
		idempotencyRecordRepository.deleteByUserIdAndExpiresAtLessThanEqual(userId, now);
		String keyHash = sha256(idempotencyKey);
		java.util.Optional<AssistantIdempotencyRecord> existing = idempotencyRecordRepository
			.findConfirmByUserAndDraftForUpdate(userId, draftId);
		if (existing.isPresent()) {
			AssistantIdempotencyRecord record = existing.get();
			if (!record.getKeyHash().equals(keyHash) || !"CONFIRMED".equals(record.getStatus())
				|| record.getDraftVersion() != draftVersion) {
				throw new BusinessException(ErrorCode.ASSISTANT_DRAFT_CONFLICT);
			}
			return new ConfirmOutcome(new AssistantDraftResponse.Result(record.getRoomId(), record.getChatRoomId()),
				true);
		}
		if (idempotencyRecordRepository.findByUserAndKeyHashForUpdate(userId, keyHash).isPresent()) {
			throw new BusinessException(ErrorCode.ASSISTANT_DRAFT_CONFLICT);
		}
		if (draft.getStatus() != AssistantDraftStatus.ACTIVE || draft.getDraftVersion() != draftVersion) {
			throw new BusinessException(ErrorCode.ASSISTANT_DRAFT_CONFLICT);
		}
		if (draft.isExpiredAt(now)) {
			throw new BusinessException(ErrorCode.ASSISTANT_DRAFT_EXPIRED);
		}
		consentGate.requireGranted(userId);
		if (draft.getPlace() == null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		AssistantIdempotencyRecord record = idempotencyRecordRepository.saveAndFlush(
			AssistantIdempotencyRecord.pending(userId, draftId, keyHash, draftVersion, draft.getExpiresAt()));
		AssistantRoomCreationResult result = assistantRoomCommand.createConfirmedRoom(new AssistantRoomCreationCommand(
			draft.getRoomType(), draft.getTitle(), draft.getDescription(), draft.getGameId(),
			draft.getExperienceLevel(),
			draft.isRulemasterLed(), draft.getStartAt(), draft.getRegion(), draft.getPlace(), draft.getCapacity()));
		draft.confirm(result.roomId(), result.chatRoomId(), now);
		record.confirm(result.roomId(), result.chatRoomId(), now);
		return new ConfirmOutcome(new AssistantDraftResponse.Result(result.roomId(), result.chatRoomId()), false);
	}

	@Transactional
	public AssistantDraftResponse update(long userId, long draftId, AssistantDraftPatchRequest request) {
		Instant now = clock.instant();
		lockUser(userId);
		AssistantDraft draft = ownedForUpdate(draftId, userId);
		if (draft.getStatus() != AssistantDraftStatus.ACTIVE || draft.getDraftVersion() != request.draftVersion()) {
			throw new BusinessException(ErrorCode.ASSISTANT_DRAFT_CONFLICT);
		}
		if (draft.isExpiredAt(now)) {
			throw new BusinessException(ErrorCode.ASSISTANT_DRAFT_EXPIRED);
		}
		if (!request.hasInputChange()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		String roomType = request.roomType() == null ? draft.getRoomType()
			: requireEnum(request.roomType(), "GAME_FOCUSED", "PERSON_FOCUSED");
		String title = request.title() == null ? draft.getTitle() : requireTitle(request.title());
		String description = request.description() == null ? draft.getDescription()
			: requireDescription(request.description());
		Long gameId = request.gameId() == null ? draft.getGameId() : requireGame(request.gameId());
		String experienceLevel = request.experienceLevel() == null ? draft.getExperienceLevel()
			: requireEnum(request.experienceLevel(), "ALL_LEVELS", "BEGINNER_WELCOME", "EXPERIENCED_PREFERRED");
		boolean rulemasterLed = request.isRulemasterLed() == null ? draft.isRulemasterLed() : request.isRulemasterLed();
		Instant startAt = request.startsAt() == null ? draft.getStartAt() : requireStartAt(request.startsAt(), now);
		String region = request.region() == null ? draft.getRegion() : normalizeRegion(request.region());
		String place = request.place() == null ? draft.getPlace() : normalizePlace(request.place());
		int capacity = request.recruitmentCapacity() == null ? draft.getCapacity()
			: requireCapacity(request.recruitmentCapacity());
		if ("GAME_FOCUSED".equals(roomType) && gameId == null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		draft.update(roomType, title, description, gameId, experienceLevel, rulemasterLed, startAt, region, place,
			capacity);
		return AssistantDraftResponse.from(draft);
	}

	@Transactional
	public void discard(long userId, long draftId) {
		Instant now = clock.instant();
		lockUser(userId);
		AssistantDraft draft = ownedForUpdate(draftId, userId);
		if (draft.getStatus() == AssistantDraftStatus.CONFIRMED) {
			throw new BusinessException(ErrorCode.ASSISTANT_DRAFT_CONFLICT);
		}
		if (draft.isExpiredAt(now)) {
			throw new BusinessException(ErrorCode.ASSISTANT_DRAFT_EXPIRED);
		}
		draft.discard();
	}

	@Transactional
	public void discardActiveForRevocation(long userId) {
		lockUser(userId);
		draftRepository.findActiveByUserIdForUpdate(userId).forEach(AssistantDraft::discard);
	}

	@EventListener
	public void onConsentRevoked(AssistantConsentRevokedEvent event) {
		discardActiveForRevocation(event.userId());
	}

	private void requireEnabled() {
		if (!properties.isGrantable()) {
			throw new BusinessException(ErrorCode.ASSISTANT_NOT_ENABLED);
		}
	}

	private void lockUser(long userId) {
		if (!userRowLockPort.lockExistingUsersInAscendingOrder(java.util.Set.of(userId)).contains(userId)) {
			throw new BusinessException(ErrorCode.ASSISTANT_DRAFT_NOT_FOUND);
		}
	}

	private AssistantDraft owned(long draftId, long userId) {
		AssistantDraft draft = draftRepository.findById(draftId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ASSISTANT_DRAFT_NOT_FOUND));
		if (draft.getUserId() != userId) {
			throw new BusinessException(ErrorCode.ASSISTANT_DRAFT_NOT_FOUND);
		}
		return draft;
	}

	private AssistantDraft ownedForUpdate(long draftId, long userId) {
		AssistantDraft draft = draftRepository.findByIdForUpdate(draftId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ASSISTANT_DRAFT_NOT_FOUND));
		if (draft.getUserId() != userId) {
			throw new BusinessException(ErrorCode.ASSISTANT_DRAFT_NOT_FOUND);
		}
		return draft;
	}

	private String requireRoomType(AssistantDraftCreateRequest request) {
		return requireEnum(request.roomType(), "GAME_FOCUSED", "PERSON_FOCUSED");
	}

	private String requireExperienceLevel(AssistantDraftCreateRequest request) {
		return requireEnum(request.experienceLevel(), "ALL_LEVELS", "BEGINNER_WELCOME", "EXPERIENCED_PREFERRED");
	}

	private String requireEnum(String value, String... allowed) {
		if (value != null && java.util.Set.of(allowed).contains(value)) {
			return value;
		}
		throw new BusinessException(ErrorCode.VALIDATION_ERROR);
	}

	private String requireTitle(AssistantDraftCreateRequest request) {
		return requireTitle(request.title());
	}

	private String requireTitle(String title) {
		String value = title == null ? null : title.strip();
		if (value == null || value.isEmpty() || value.length() > 100) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		return value;
	}

	private String requireDescription(String description) {
		if (description.length() > 255) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		return description;
	}

	private boolean requireRulemasterLed(AssistantDraftCreateRequest request) {
		if (request.isRulemasterLed() == null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		return request.isRulemasterLed();
	}

	private int requireCapacity(AssistantDraftCreateRequest request) {
		return requireCapacity(request.recruitmentCapacity());
	}

	private int requireCapacity(Integer capacity) {
		if (capacity == null || capacity < 1 || capacity > 10) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		return capacity;
	}

	private Instant requireStartAt(AssistantDraftCreateRequest request, Instant now) {
		return requireStartAt(request.startsAt(), now);
	}

	private Instant requireStartAt(Instant startsAt, Instant now) {
		if (startsAt == null || !startsAt.isAfter(now)) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		return startsAt;
	}

	private String normalizeRegion(String region) {
		String value = region == null ? "홍대" : region;
		if (!java.util.Set.of("홍대", "강남", "건대", "잠실").contains(value)) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		return value;
	}

	private String normalizePlace(String place) {
		if (place == null) {
			return null;
		}
		String value = place.strip();
		if (value.isEmpty() || value.length() > 100) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		return value;
	}

	private Long requireGame(Long gameId) {
		if (gameId == null || gameQuery.findSummaryById(gameId).isEmpty()) {
			throw new BusinessException(ErrorCode.GAME_NOT_FOUND);
		}
		return gameId;
	}

	private String sha256(String value) {
		try {
			byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(digest);
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	public record ConfirmOutcome(AssistantDraftResponse.Result result, boolean replayed) {
	}
}
