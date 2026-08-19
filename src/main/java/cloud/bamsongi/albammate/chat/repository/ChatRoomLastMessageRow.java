package cloud.bamsongi.albammate.chat.repository;

/**
 * 방별 마지막 메시지 배치 조회의 native query 결과 projection이다. TIMESTAMP WITH TIME ZONE 컬럼을 그대로
 * 노출하면, Hibernate가 다이얼렉트(H2Dialect는 {@code OffsetDateTime}, PostgreSQLDialect는
 * {@code Instant})별로 다른 기본 Java 타입을 추론해 반환 타입이 환경마다 달라지고, Spring Data의
 * {@code ProjectingMethodInterceptor}가 어느 한쪽으로 선언해도 다른 쪽 환경에서 변환기를 찾지 못해
 * {@code UnsupportedOperationException}이 발생한다. native query에서 epoch milliseconds(UTC 기준
 * 절대 시각, 타임존 표현과 무관)로 명시적으로 캐스팅해 {@code Long}으로 받으면 두 환경에서 항상 같은
 * JDBC 타입이 반환되므로 이 모호성을 피한다.
 */
public interface ChatRoomLastMessageRow {

	Long getRoomId();

	String getContent();

	Long getCreatedAtEpochMilli();
}
