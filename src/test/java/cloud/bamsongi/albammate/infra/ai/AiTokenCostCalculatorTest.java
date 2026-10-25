package cloud.bamsongi.albammate.infra.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class AiTokenCostCalculatorTest {

	@Test
	void usage_비용을_가격_snapshot으로_계산하고_micro_USD로_올림한다() {
		assertEquals(new BigDecimal("0.000015"), AiTokenCostCalculator.estimate(
			10, 5, new BigDecimal("1.00"), new BigDecimal("1.00")));
		assertEquals(new BigDecimal("0.000002"), AiTokenCostCalculator.estimate(
			1, 0, new BigDecimal("1.000001"), BigDecimal.ZERO));
	}

	@Test
	void T2_최대_비용은_설정된_input_output_token_상한으로_계산한다() {
		assertEquals(new BigDecimal("0.004352"), AiProviderSettings.fakeDefaults().maximumCostUsd());
	}

	@Test
	void T2_최대_예약은_cache_write를_포함한_가장_높은_input_단가를_사용한다() {
		AiProviderSettings settings = new AiProviderSettings(
			"local-openai", true, true, true, true, "AI-02-POLICY-V1", "https://example.test/ai-policy",
			"gpt-5.6-luna", java.time.Duration.ofSeconds(10), 0, false, "TEST-PRICING-V1",
			new BigDecimal("1.00"), new BigDecimal("2.00"), new BigDecimal("0.25"),
			new BigDecimal("4.00"), 2_000, 100, "zero-data-retention");

		assertEquals(new BigDecimal("0.008200"), AiTokenCostCalculator.maximumCost(settings));
	}

	@Test
	void T3_usage_cache_read와_cache_write를_각각_정산하고_분류가_없으면_일반_input_가격을_쓴다() {
		assertEquals(new BigDecimal("0.000082"), AiTokenCostCalculator.estimate(
			10, 3, 2, 5, new BigDecimal("8.00"), new BigDecimal("3.00"),
			new BigDecimal("1.00"), new BigDecimal("12.00")));
		assertEquals(new BigDecimal("0.000095"), AiTokenCostCalculator.estimate(
			10, null, null, 5, new BigDecimal("8.00"), new BigDecimal("3.00"),
			new BigDecimal("1.00"), new BigDecimal("12.00")));
	}

	@Test
	void 음수_token이나_가격은_비용_계산을_거절한다() {
		assertThrows(IllegalArgumentException.class, () -> AiTokenCostCalculator.estimate(
			-1, 0, BigDecimal.ONE, BigDecimal.ONE));
		assertThrows(IllegalArgumentException.class, () -> AiTokenCostCalculator.estimate(
			0, 0, new BigDecimal("-1"), BigDecimal.ONE));
	}
}
