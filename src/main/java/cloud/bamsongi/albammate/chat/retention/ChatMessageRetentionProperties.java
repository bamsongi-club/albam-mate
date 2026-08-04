package cloud.bamsongi.albammate.chat.retention;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 채팅 만료 삭제의 일일 주기, 처리 상한과 측정 기반 시간 경계를 바인딩한다. */
@Component
@Validated
@ConfigurationProperties(prefix = "app.chat.retention")
public class ChatMessageRetentionProperties {

	private boolean enabled = true;

	@NotBlank private String cron = "0 0 3 * * *";

	@Min(1) private int maxRoomsPerRun = 50;

	@Min(1) private int maxMessagesPerRun = 5_000;

	@Min(1) private int messageChunkSize = 100;

	@NotNull @DurationMin(nanos = 1)
	private Duration lockAtMostFor = Duration.ofSeconds(5);

	@NotNull @DurationMin(nanos = 1)
	private Duration lockAtLeastFor = Duration.ofSeconds(5);

	@NotNull @DurationMin(nanos = 1)
	private Duration executionWarningThreshold = Duration.ofSeconds(1);

	@NotNull @DurationMin(nanos = 1)
	private Duration maxRunDuration = Duration.ofSeconds(3);

	/** 반복 batch가 임대 안에서 끝나야 하므로 실행 상한은 항상 잠금 임대보다 짧다. */
	@AssertTrue public boolean isRunDurationWithinLockLease() {
		if (maxRunDuration == null || lockAtMostFor == null) {
			return true;
		}
		return maxRunDuration.compareTo(lockAtMostFor) < 0;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getCron() {
		return cron;
	}

	public void setCron(String cron) {
		this.cron = cron;
	}

	public int getMaxRoomsPerRun() {
		return maxRoomsPerRun;
	}

	public void setMaxRoomsPerRun(int maxRoomsPerRun) {
		this.maxRoomsPerRun = maxRoomsPerRun;
	}

	public int getMaxMessagesPerRun() {
		return maxMessagesPerRun;
	}

	public void setMaxMessagesPerRun(int maxMessagesPerRun) {
		this.maxMessagesPerRun = maxMessagesPerRun;
	}

	public int getMessageChunkSize() {
		return messageChunkSize;
	}

	public void setMessageChunkSize(int messageChunkSize) {
		this.messageChunkSize = messageChunkSize;
	}

	public Duration getLockAtMostFor() {
		return lockAtMostFor;
	}

	public void setLockAtMostFor(Duration lockAtMostFor) {
		this.lockAtMostFor = lockAtMostFor;
	}

	public Duration getLockAtLeastFor() {
		return lockAtLeastFor;
	}

	public void setLockAtLeastFor(Duration lockAtLeastFor) {
		this.lockAtLeastFor = lockAtLeastFor;
	}

	public Duration getExecutionWarningThreshold() {
		return executionWarningThreshold;
	}

	public void setExecutionWarningThreshold(Duration executionWarningThreshold) {
		this.executionWarningThreshold = executionWarningThreshold;
	}

	public Duration getMaxRunDuration() {
		return maxRunDuration;
	}

	public void setMaxRunDuration(Duration maxRunDuration) {
		this.maxRunDuration = maxRunDuration;
	}
}
