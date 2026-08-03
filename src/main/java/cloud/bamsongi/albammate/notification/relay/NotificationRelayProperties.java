package cloud.bamsongi.albammate.notification.relay;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** relay 실행 주기와 한 실행의 상한을 운영 정본 값으로 바인딩한다. */
@Component
@Validated
@ConfigurationProperties(prefix = "app.notification.relay")
public class NotificationRelayProperties {

	private boolean enabled = true;

	@NotNull @DurationMin(nanos = 1)
	private Duration pollInterval = Duration.ofSeconds(5);

	@Min(1) private int maxEventsPerRun = 50;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public Duration getPollInterval() {
		return pollInterval;
	}

	public void setPollInterval(Duration pollInterval) {
		this.pollInterval = pollInterval;
	}

	public int getMaxEventsPerRun() {
		return maxEventsPerRun;
	}

	public void setMaxEventsPerRun(int maxEventsPerRun) {
		this.maxEventsPerRun = maxEventsPerRun;
	}
}
