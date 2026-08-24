package cloud.bamsongi.albammate.chat.repository;

import java.time.Instant;

/**
 * 방별 마지막 메시지 배치 조회 결과 한 행이다.
 *
 * <p>TIMESTAMP WITH TIME ZONE 컬럼을 Spring Data native @Query interface projection으로 노출하면,
 * Hibernate가 다이얼렉트(H2Dialect는 {@code OffsetDateTime}, PostgreSQLDialect는 {@code Instant})별로
 * 다른 기본 Java 타입을 추론해 반환 타입이 환경마다 달라지고, {@code ProjectingMethodInterceptor}가 어느
 * 한쪽으로 선언해도 다른 쪽 환경에서 변환기를 찾지 못해 {@code UnsupportedOperationException}이 발생한다.
 * epoch milliseconds로 우회하면 이 모호성은 피하지만, 저장 시각의 하위 밀리초(PostgreSQL TIMESTAMPTZ의
 * 마이크로초 정밀도)를 잃는다.
 *
 * <p>두 문제를 함께 피하기 위해 이 조회는 Spring Data 파생 쿼리 대신 {@code NamedParameterJdbcTemplate}으로
 * 직접 매핑한다({@code ChatRoomPreviewQueryService} 참고). {@code ResultSet.getObject(label,
 * OffsetDateTime.class)}는 두 드라이버 모두 표준 JDBC 타입 변환만 사용해 다이얼렉트에 무관하게 동작하고,
 * PostgreSQL TIMESTAMPTZ의 저장 정밀도(마이크로초)를 그대로 보존한다. {@code notification} 모듈의
 * {@code NotificationQueryRepository}가 같은 패턴을 이미 사용한다.
 */
public record ChatRoomLastMessageRow(Long roomId, String content, Instant createdAt) {
}
