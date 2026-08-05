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

	@Min(1) private int maxLockSectionsPerRun = 30;

	@NotNull @DurationMin(nanos = 1)
	private Duration lockAtMostFor = Duration.ofMinutes(2);

	@NotNull @DurationMin(nanos = 1)
	private Duration lockAtLeastFor = Duration.ofSeconds(5);

	@NotNull @DurationMin(nanos = 1)
	private Duration executionWarningThreshold = Duration.ofSeconds(30);

	@NotNull @DurationMin(nanos = 1)
	private Duration maxRunDuration = Duration.ofMinutes(1);

	@NotNull @DurationMin(seconds = 1)
	private Duration queryTimeout = Duration.ofSeconds(10);

	/**
	 * 한 잠금 구간이 임대 안에서 끝나야 한다. 마지막 상한 확인 뒤에도 진행 중인 chunk의 조회·삭제·완료
	 * 질의가 남으므로, 실행 상한에 질의 시간 상한 세 번을 더한 값이 임대보다 짧아야 한다.
	 */
	@AssertTrue public boolean isRunDurationWithinLockLease() {
		if (maxRunDuration == null || queryTimeout == null || lockAtMostFor == null) {
			return true;
		}
		return maxRunDuration.plus(queryTimeout.multipliedBy(3)).compareTo(lockAtMostFor) < 0;
	}

	/**
	 * 실행 상한에서 중단된 잠금 구간은 최소 잠금 시간이 지난 뒤에야 해제되므로, 실행 상한이 최소
	 * 잠금 시간보다 짧으면 중단 시점에 잠금이 아직 유지돼 같은 cron 실행이 잠금을 다시 얻지 못한다.
	 */
	@AssertTrue public boolean isMaxRunDurationAtLeastLockAtLeastFor() {
		if (maxRunDuration == null || lockAtLeastFor == null) {
			return true;
		}
		return maxRunDuration.compareTo(lockAtLeastFor) >= 0;
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

	public Duration getQueryTimeout() {
		return queryTimeout;
	}

	public void setQueryTimeout(Duration queryTimeout) {
		this.queryTimeout = queryTimeout;
	}

	public int getMaxLockSectionsPerRun() {
		return maxLockSectionsPerRun;
	}

	public void setMaxLockSectionsPerRun(int maxLockSectionsPerRun) {
		this.maxLockSectionsPerRun = maxLockSectionsPerRun;
	}
}
