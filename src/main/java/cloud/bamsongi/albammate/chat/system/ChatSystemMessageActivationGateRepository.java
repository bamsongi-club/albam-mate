package cloud.bamsongi.albammate.chat.system;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatSystemMessageActivationGateRepository
	extends JpaRepository<ChatSystemMessageActivationGate, String> {

	/**
	 * gate 판정을 DB 시계 {@code clock_timestamp()}로 한 번에 고정한다. 애플리케이션 {@code Clock} 기준
	 * {@code occurredAt}은 이 비교에 쓰지 않는다. 행이 없거나 {@code enabled_at}이 비어 있으면 빈 값을 반환하고,
	 * 호출자는 이를 비활성으로 취급한다.
	 */
	@Query(value = "SELECT enabled_at <= clock_timestamp() FROM chat_system_message_activation "
		+ "WHERE gate_name = :gateName AND enabled_at IS NOT NULL", nativeQuery = true)
	Optional<Boolean> isActiveNow(@Param("gateName")
	String gateName);
}
