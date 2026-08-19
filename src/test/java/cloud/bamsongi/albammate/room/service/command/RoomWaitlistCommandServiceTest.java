package cloud.bamsongi.albammate.room.service.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.service.RoomOptimisticLockRetrier;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.OptimisticLockException;

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
	void T3_승격_재시도_최종_성공은_외부_요청당_accepted_한번만_기록한다() {
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		Metrics.addRegistry(meterRegistry);
		try {
			RoomParticipationCancelExecutor executor = mock(RoomParticipationCancelExecutor.class);
			RoomParticipationResponse response = new RoomParticipationResponse(
				7L, ParticipationStatus.CANCELED, RoomStatus.RECRUITING, 1, 2);
			when(executor.cancelParticipation(eq(11L), eq(7L), eq(REQUEST_TIME), any(Runnable.class)))
				.thenAnswer(invocation -> {
					((Runnable)invocation.getArgument(3)).run();
					throw new OptimisticLockException();
				})
				.thenAnswer(invocation -> {
					((Runnable)invocation.getArgument(3)).run();
					return response;
				});

			assertEquals(response, promotionService(executor).cancelParticipation(11L, 7L));

			assertEquals(1.0, operationCount(meterRegistry, "promote", "accepted"));
			assertEquals(0.0, operationCount(meterRegistry, "promote", "failed"));
		} finally {
			Metrics.removeRegistry(meterRegistry);
			meterRegistry.close();
		}
	}

	@Test
	void T3_승격_재시도_소진은_외부_요청당_failed_한번만_기록한다() {
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		Metrics.addRegistry(meterRegistry);
		try {
			RoomParticipationCancelExecutor executor = mock(RoomParticipationCancelExecutor.class);
			when(executor.cancelParticipation(eq(11L), eq(7L), eq(REQUEST_TIME), any(Runnable.class)))
				.thenAnswer(invocation -> {
					((Runnable)invocation.getArgument(3)).run();
					throw new OptimisticLockException();
				})
				.thenAnswer(invocation -> {
					((Runnable)invocation.getArgument(3)).run();
					throw new ObjectOptimisticLockingFailureException(Room.class, 7L);
				})
				.thenAnswer(invocation -> {
					((Runnable)invocation.getArgument(3)).run();
					throw new OptimisticLockException();
				});

			assertThrows(BusinessException.class, () -> promotionService(executor).cancelParticipation(11L, 7L));

			assertEquals(0.0, operationCount(meterRegistry, "promote", "accepted"));
			assertEquals(1.0, operationCount(meterRegistry, "promote", "failed"));
		} finally {
			Metrics.removeRegistry(meterRegistry);
			meterRegistry.close();
		}
	}

	@Test
	void T3_승격_뒤_마지막_미승격_실패도_외부_요청_failed_한번으로_기록한다() {
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		Metrics.addRegistry(meterRegistry);
		try {
			RoomParticipationCancelExecutor executor = mock(RoomParticipationCancelExecutor.class);
			when(executor.cancelParticipation(eq(11L), eq(7L), eq(REQUEST_TIME), any(Runnable.class)))
				.thenAnswer(invocation -> {
					((Runnable)invocation.getArgument(3)).run();
					throw new OptimisticLockException();
				})
				.thenThrow(new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));

			assertThrows(BusinessException.class, () -> promotionService(executor).cancelParticipation(11L, 7L));

			assertEquals(0.0, operationCount(meterRegistry, "promote", "accepted"));
			assertEquals(1.0, operationCount(meterRegistry, "promote", "failed"));
		} finally {
			Metrics.removeRegistry(meterRegistry);
			meterRegistry.close();
		}
	}

	private RoomParticipationCancelService promotionService(RoomParticipationCancelExecutor executor) {
		return new RoomParticipationCancelService(
			executor,
			new RoomCommandExecutionCoordinator(
				java.time.Clock.fixed(REQUEST_TIME, java.time.ZoneOffset.UTC), new RoomOptimisticLockRetrier()),
			new RoomWaitlistMetrics(Metrics.globalRegistry));
	}

	private double operationCount(SimpleMeterRegistry meterRegistry, String operation, String outcome) {
		var counter = meterRegistry.find("room.waitlist.operations")
			.tags("operation", operation, "outcome", outcome)
			.counter();
		return counter == null ? 0.0 : counter.count();
	}
}
