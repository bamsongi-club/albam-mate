package cloud.bamsongi.albammate.room.service.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.contract.RoomChangeEventRecorder;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistCandidateProjection;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RoomWaitlistCommandServiceTest {

	private static final Instant REQUEST_TIME = Instant.parse("2026-08-05T00:00:00Z");

	@Test
	void T3_대기열_진입은_커밋된_결과만_유한_operation_outcome_metric으로_기록한다() {
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		Metrics.addRegistry(meterRegistry);
		RoomWaitlistRegistrationCoordinator registrationCoordinator = mock(RoomWaitlistRegistrationCoordinator.class);
		when(registrationCoordinator.register(11L, 7L)).thenReturn(
			new RoomWaitlistCommandService.RegistrationResult(null, true));
		when(registrationCoordinator.register(12L, 7L))
			.thenThrow(new BusinessException(ErrorCode.WAITLIST_NOT_AVAILABLE));
		when(registrationCoordinator.register(13L, 7L)).thenThrow(new IllegalStateException("database unavailable"));
		when(registrationCoordinator.register(14L, 7L))
			.thenThrow(new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
		when(registrationCoordinator.register(15L, 7L))
			.thenThrow(new BusinessException(ErrorCode.ROOM_CONCURRENT_MODIFICATION));
		RoomCommandExecutionCoordinator executionCoordinator = mock(RoomCommandExecutionCoordinator.class);
		when(executionCoordinator.execute(eq(7L), eq("room_waitlist_cancel_retry"), any()))
			.thenReturn(null)
			.thenThrow(new BusinessException(ErrorCode.WAITLIST_ENTRY_NOT_FOUND))
			.thenThrow(new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR))
			.thenThrow(new BusinessException(ErrorCode.ROOM_CONCURRENT_MODIFICATION))
			.thenThrow(new IllegalStateException("database unavailable"));
		RoomWaitlistCommandService service = new RoomWaitlistCommandService(
			registrationCoordinator,
			mock(RoomWaitlistCancelExecutor.class),
			executionCoordinator);

		try {
			service.register(11L, 7L);
			assertThrows(BusinessException.class, () -> service.register(12L, 7L));
			assertThrows(IllegalStateException.class, () -> service.register(13L, 7L));
			assertThrows(BusinessException.class, () -> service.register(14L, 7L));
			assertThrows(BusinessException.class, () -> service.register(15L, 7L));
			service.cancel(11L, 7L);
			assertThrows(BusinessException.class, () -> service.cancel(12L, 7L));
			assertThrows(BusinessException.class, () -> service.cancel(13L, 7L));
			assertThrows(BusinessException.class, () -> service.cancel(14L, 7L));
			assertThrows(IllegalStateException.class, () -> service.cancel(15L, 7L));

			assertEquals(1.0, meterRegistry.get("room.waitlist.operations")
				.tags("operation", "join", "outcome", "accepted").counter().count());
			assertEquals(1.0, meterRegistry.get("room.waitlist.operations")
				.tags("operation", "join", "outcome", "rejected").counter().count());
			assertEquals(3.0, meterRegistry.get("room.waitlist.operations")
				.tags("operation", "join", "outcome", "failed").counter().count());
			assertEquals(1.0, meterRegistry.get("room.waitlist.operations")
				.tags("operation", "cancel", "outcome", "accepted").counter().count());
			assertEquals(1.0, meterRegistry.get("room.waitlist.operations")
				.tags("operation", "cancel", "outcome", "rejected").counter().count());
			assertEquals(3.0, meterRegistry.get("room.waitlist.operations")
				.tags("operation", "cancel", "outcome", "failed").counter().count());
			assertTrue(meterRegistry.find("room.waitlist.operations").meters().stream()
				.allMatch(meter -> meter.getId().getTags().stream()
					.allMatch(tag -> "operation".equals(tag.getKey()) || "outcome".equals(tag.getKey()))));
		} finally {
			Metrics.removeRegistry(meterRegistry);
			meterRegistry.close();
		}
	}

	@Test
	void T3_승격_rollback은_accepted가_아니라_failed로_기록한다() {
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		Metrics.addRegistry(meterRegistry);
		TransactionSynchronizationManager.initSynchronization();
		try {
			promotionExecutor(new RoomWaitlistMetrics(Metrics.globalRegistry)).cancelParticipation(11L, 7L,
				REQUEST_TIME);

			assertEquals(0.0, operationCount(meterRegistry, "promote", "accepted"));
			TransactionSynchronizationManager.getSynchronizations().forEach(
				synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
			assertEquals(1.0, operationCount(meterRegistry, "promote", "failed"));
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
			Metrics.removeRegistry(meterRegistry);
			meterRegistry.close();
		}
	}

	@Test
	void T3_승격_commit은_afterCompletion_뒤에만_accepted로_기록한다() {
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		Metrics.addRegistry(meterRegistry);
		TransactionSynchronizationManager.initSynchronization();
		try {
			promotionExecutor(new RoomWaitlistMetrics(Metrics.globalRegistry)).cancelParticipation(11L, 7L,
				REQUEST_TIME);

			assertEquals(0.0, operationCount(meterRegistry, "promote", "accepted"));
			TransactionSynchronizationManager.getSynchronizations().forEach(
				synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
			assertEquals(1.0, operationCount(meterRegistry, "promote", "accepted"));
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
			Metrics.removeRegistry(meterRegistry);
			meterRegistry.close();
		}
	}

	private RoomParticipationCancelExecutor promotionExecutor(RoomWaitlistMetrics metrics) {
		RoomRepository roomRepository = mock(RoomRepository.class);
		ParticipationRepository participationRepository = mock(ParticipationRepository.class);
		RoomWaitlistRepository waitlistRepository = mock(RoomWaitlistRepository.class);
		Room room = mock(Room.class);
		Participation leaving = mock(Participation.class);
		RoomWaitlistCandidateProjection waiting = mock(RoomWaitlistCandidateProjection.class);
		when(roomRepository.findById(7L)).thenReturn(Optional.of(room));
		when(room.getHostUserId()).thenReturn(1L);
		when(room.getId()).thenReturn(7L);
		when(room.getStartAt()).thenReturn(REQUEST_TIME.plusSeconds(3600));
		when(room.getStatus()).thenReturn(RoomStatus.RECRUITING);
		when(room.getTotalParticipantCount()).thenReturn(2);
		when(room.getRemainingRecruitmentSeats()).thenReturn(0);
		when(leaving.getStatus()).thenReturn(ParticipationStatus.ACTIVE);
		when(participationRepository.findByRoomIdAndUserId(7L, 11L)).thenReturn(Optional.of(leaving));
		when(waiting.getUserId()).thenReturn(22L);
		when(waiting.getQueueOrder()).thenReturn(1L);
		when(waitlistRepository.findFirstWaitingByRoomId(7L)).thenReturn(Optional.of(waiting));
		when(waitlistRepository.promoteWaiting(7L, 22L, 1L, REQUEST_TIME)).thenReturn(1);
		when(participationRepository.findByRoomIdAndUserId(7L, 22L)).thenReturn(Optional.empty());
		return new RoomParticipationCancelExecutor(
			roomRepository,
			participationRepository,
			waitlistRepository,
			mock(RoomChangeEventRecorder.class),
			metrics);
	}

	private double operationCount(SimpleMeterRegistry meterRegistry, String operation, String outcome) {
		var counter = meterRegistry.find("room.waitlist.operations")
			.tags("operation", operation, "outcome", outcome)
			.counter();
		return counter == null ? 0.0 : counter.count();
	}
}
