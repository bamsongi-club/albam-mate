package cloud.bamsongi.albammate.notification.recovery;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** notification-ops profile의 유일한 one-shot adapter다. */
@Component
@Profile("notification-ops")
@Slf4j
@RequiredArgsConstructor
public class NotificationOpsRunner implements ApplicationRunner, ExitCodeGenerator {

	@NonNull private final NotificationOutboxRecoveryService recoveryService;
	@NonNull private final Environment environment;

	private int exitCode = 1;

	@Override
	public void run(ApplicationArguments arguments) {
		try {
			NotificationOutboxRecoveryRequest request = NotificationOpsArguments.from(environment);
			NotificationOutboxRecoveryResult result = request.action() == NotificationRecoveryAction.INSPECT
				|| request.dryRun()
					? recoveryService.preview(request)
					: recoveryService.execute(request);
			printResult(result);
			exitCode = 0;
		} catch (NotificationOutboxRecoveryInputException exception) {
			System.err.println("notification outbox operation input rejected");
			exitCode = 2;
		} catch (RuntimeException exception) {
			log.error("event=notification_outbox_operation_failed exceptionClass={}",
				exception.getClass().getSimpleName());
			System.err.println("notification outbox operation failed");
			exitCode = 1;
		}
	}

	private static void printResult(NotificationOutboxRecoveryResult result) {
		System.out.println("notification outbox operation completed eventIds=" + result.eventIds()
			+ " eligibleCount=" + result.eligibleCount() + " changedCount=" + result.changedCount());
		for (NotificationOutboxRecoveryItem item : result.items()) {
			System.out.println("eventId=" + item.eventId()
				+ " status=" + item.status()
				+ " eventType=" + item.eventType()
				+ " occurredAt=" + item.occurredAt()
				+ " expiresAt=" + item.expiresAt()
				+ " failureCount=" + item.failureCount()
				+ " totalFailureCount=" + item.totalFailureCount()
				+ " lastFailureCode=" + item.lastFailureCode()
				+ " reprocessable=" + item.reprocessable()
				+ " eligible=" + item.eligible());
		}
	}

	@Override
	public int getExitCode() {
		return exitCode;
	}
}
