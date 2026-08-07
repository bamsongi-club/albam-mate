package cloud.bamsongi.albammate.room.statuscorrection;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** ROOM 상태 보정의 Trigger와 잠금·관측 입력을 검증해 바인딩한다. */
@Component
@Validated
@ConfigurationProperties(prefix = "app.room.status-correction")
public class RoomStatusCorrectionProperties {

	@NotBlank @Pattern(regexp = "room-status-correction") private String lockName;

	@NotNull @DurationMin(nanos = 1)
	private Duration triggerDelay;

	@NotNull @DurationMin(nanos = 0)
	private Duration triggerJitter;

	@NotNull @DurationMin(nanos = 1)
	private Duration lockAtMostFor;

	@NotNull @DurationMin(nanos = 1)
	private Duration executionWarningThreshold;

	/** #390이 초기 운영값을 확정하기 전까지는 테스트·측정에서만 명시적으로 주입한다. */
	@Min(1) private Integer candidateLimit;

	public String getLockName() {
		return lockName;
	}

	public void setLockName(String lockName) {
		this.lockName = lockName;
	}

	public Duration getTriggerDelay() {
		return triggerDelay;
	}

	public void setTriggerDelay(Duration triggerDelay) {
		this.triggerDelay = triggerDelay;
	}

	public Duration getTriggerJitter() {
		return triggerJitter;
	}

	public void setTriggerJitter(Duration triggerJitter) {
		this.triggerJitter = triggerJitter;
	}

	public Duration getLockAtMostFor() {
		return lockAtMostFor;
	}

	public void setLockAtMostFor(Duration lockAtMostFor) {
		this.lockAtMostFor = lockAtMostFor;
	}

	public Duration getExecutionWarningThreshold() {
		return executionWarningThreshold;
	}

	public void setExecutionWarningThreshold(Duration executionWarningThreshold) {
		this.executionWarningThreshold = executionWarningThreshold;
	}

	public Integer getCandidateLimit() {
		return candidateLimit;
	}

	public void setCandidateLimit(Integer candidateLimit) {
		this.candidateLimit = candidateLimit;
	}
}
