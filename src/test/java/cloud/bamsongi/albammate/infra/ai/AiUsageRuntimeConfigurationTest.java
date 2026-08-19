package cloud.bamsongi.albammate.infra.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import cloud.bamsongi.albammate.assistant.contract.AssistantCostWarningEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AiUsageRuntimeConfigurationTest {

	@Test
	void T3_cost_warning은_Spring_event_consumer가_월별로_한번만_Micrometer에_기록한다() throws Exception {
		try (AnnotationConfigApplicationContext context = usageObservationContext()) {
			AiCostWarningEventSink sink = context.getBean(AiCostWarningEventSink.class);
			SimpleMeterRegistry meterRegistry = context.getBean(SimpleMeterRegistry.class);
			AssistantCostWarningEvent warning = new AssistantCostWarningEvent(
				YearMonth.of(2026, 8), new BigDecimal("4.10"), new BigDecimal("4.00"));

			publishConcurrently(sink, warning);
			sink.record(warning);

			assertEquals(1.0, meterRegistry.get("assistant.cost.warning.events")
				.tags("quota_month", "2026-08", "warning_threshold_usd", "4.00").counter().count());
		}
	}

	private AnnotationConfigApplicationContext usageObservationContext() {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.registerBean(SimpleMeterRegistry.class);
		context.scan("cloud.bamsongi.albammate.infra.ai");
		context.refresh();
		return context;
	}

	private void publishConcurrently(AiCostWarningEventSink sink, AssistantCostWarningEvent warning) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(8);
		CountDownLatch start = new CountDownLatch(1);
		List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
		try {
			for (int index = 0; index < 32; index++) {
				futures.add(executor.submit(() -> {
					start.await();
					sink.record(warning);
					return null;
				}));
			}
			start.countDown();
			for (java.util.concurrent.Future<?> future : futures) {
				future.get();
			}
		} finally {
			executor.shutdown();
			assertEquals(true, executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}
}
