package cloud.bamsongi.albammate.monitoring;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.monitoring.dependency-health", name = "enabled", havingValue = "true", matchIfMissing = true)
class DependencyHealthSchedulingConfiguration {

	@Bean(name = "dependencyHealthTaskScheduler", destroyMethod = "shutdown")
	ThreadPoolTaskScheduler dependencyHealthTaskScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("dependency-health-");
		scheduler.setWaitForTasksToCompleteOnShutdown(false);
		scheduler.setAwaitTerminationSeconds(2);
		return scheduler;
	}

	@Bean
	ApplicationRunner dependencyHealthSamplingRunner(DependencyHealthSampler sampler,
		@Qualifier("dependencyHealthTaskScheduler") TaskScheduler dependencyHealthTaskScheduler,
		@Value("${app.monitoring.dependency-health.poll-interval:10s}")
		Duration pollInterval) {
		return arguments -> dependencyHealthTaskScheduler.scheduleWithFixedDelay(sampler::sample, pollInterval);
	}
}
