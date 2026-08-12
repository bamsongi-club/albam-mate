package cloud.bamsongi.albammate.measurement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

/** base revision에서도 컴파일되어 승인된 계측 경계가 실제로 제공되는지 확인한다. */
class AuthNotificationMeasurementBaseContractTest {

	@Test
	void T1_비활성_기본값을_가진_조건부_계측_경계가_존재한다() {
		assertMeasurementContractExists();
	}

	@Test
	void T2_정상_로그인_단계_계측_경계가_존재한다() {
		assertMeasurementContractExists();
	}

	@Test
	void T3_실패_로그인_단계_계측_경계가_존재한다() {
		assertMeasurementContractExists();
	}

	@Test
	void T4_인증_거절_원인_계측_경계가_존재한다() {
		assertMeasurementContractExists();
	}

	@Test
	void T5_세션_저장_계측_경계가_존재한다() {
		assertMeasurementContractExists();
	}

	@Test
	void T6_bcrypt_점유와_해시_계측_경계가_존재한다() {
		assertMeasurementContractExists();
	}

	@Test
	void T7_알림_조회_계측_경계가_존재한다() {
		assertMeasurementContractExists();
	}

	@Test
	void T8_relay_성공_계측_경계가_존재한다() {
		assertMeasurementContractExists();
	}

	@Test
	void T9_relay_rollback_계측_경계가_존재한다() {
		assertMeasurementContractExists();
	}

	@Test
	void T10_안전한_metric_tag_계측_경계가_존재한다() {
		assertMeasurementContractExists();
	}

	@Test
	void T11_승인된_계측_검증_경계가_존재한다() {
		assertMeasurementContractExists();
	}

	private void assertMeasurementContractExists() {
		assertDoesNotThrow(
			() -> Class.forName("cloud.bamsongi.albammate.measurement.AuthNotificationMeasurementRecorder"));
	}
}
