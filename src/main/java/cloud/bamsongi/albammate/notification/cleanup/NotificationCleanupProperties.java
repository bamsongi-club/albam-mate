package cloud.bamsongi.albammate.notification.cleanup;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** cleanup 실행 주기와 종류별 batch 상한을 운영 정본 값으로 바인딩한다. */
@Component
@Validated
@ConfigurationProperties(prefix = "app.notification.cleanup")
public class NotificationCleanupProperties {

	@NotNull @DurationMin(nanos = 1)
	private Duration interval = Duration.ofHours(1);

	@NotNull @DurationMin(nanos = 0)
	private Duration jitter = Duration.ofMinutes(5);

	@Min(1) private int batchSize = 500;

	@Min(1) private int maxBatchesPerTarget = 5;

	public Duration getInterval() {
		return interval;
	}

	public void setInterval(Duration interval) {
		this.interval = interval;
	}

	public Duration getJitter() {
		return jitter;
	}

	public void setJitter(Duration jitter) {
		this.jitter = jitter;
	}

	public int getBatchSize() {
		return batchSize;
	}

	public void setBatchSize(int batchSize) {
		this.batchSize = batchSize;
	}

	public int getMaxBatchesPerTarget() {
		return maxBatchesPerTarget;
	}

	public void setMaxBatchesPerTarget(int maxBatchesPerTarget) {
		this.maxBatchesPerTarget = maxBatchesPerTarget;
	}
}
