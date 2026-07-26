package cloud.bamsongi.albammate.global.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
class TimeConfigTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-07-26T03:00:00Z");

    @Autowired private Clock clock;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private Environment environment;

    @Test
    void 운영_Clock은_UTC를_기준으로_한다() {
        assertEquals(ZoneOffset.UTC, clock.getZone());

        Instant before = Instant.now(Clock.systemUTC());
        Instant current = clock.instant();
        Instant after = Instant.now(Clock.systemUTC());

        assertFalse(current.isBefore(before));
        assertFalse(current.isAfter(after));
    }

    @Test
    void JVM_시작_옵션과_기본_시간대는_UTC로_설정된다() {
        assertEquals("UTC", System.getProperty("user.timezone"));
        assertEquals(ZoneId.of("UTC"), ZoneId.systemDefault());
    }

    @Test
    void 고정된_Clock을_주입하면_현재_시각을_재현한다() {
        Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        CurrentTime currentTime = new CurrentTime(fixedClock);

        assertEquals(FIXED_INSTANT, currentTime.now());
    }

    @Test
    void 서로_다른_오프셋의_같은_순간은_같은_Instant로_정규화된다() throws Exception {
        Instant seoulTime = objectMapper.readValue("\"2026-07-26T12:00:00+09:00\"", Instant.class);
        Instant utcTime = objectMapper.readValue("\"2026-07-26T03:00:00Z\"", Instant.class);

        assertEquals(utcTime, seoulTime);
    }

    @Test
    void 소수초가_포함된_서로_다른_오프셋의_같은_순간도_같은_Instant로_정규화된다() throws Exception {
        Instant seoulTime =
                objectMapper.readValue("\"2026-07-26T12:00:00.123+09:00\"", Instant.class);
        Instant utcTime = objectMapper.readValue("\"2026-07-26T03:00:00.123Z\"", Instant.class);

        assertEquals(utcTime, seoulTime);
    }

    @Test
    void 소문자_t와_z인_요청_시각도_같은_Instant로_정규화된다() throws Exception {
        Instant lowercaseTime = objectMapper.readValue("\"2026-07-26t03:00:00z\"", Instant.class);
        Instant uppercaseTime = objectMapper.readValue("\"2026-07-26T03:00:00Z\"", Instant.class);

        assertEquals(uppercaseTime, lowercaseTime);
    }

    @Test
    void 응답_시각은_Asia_Seoul의_오프셋으로_직렬화된다() throws Exception {
        String response = objectMapper.writeValueAsString(FIXED_INSTANT);

        assertEquals("\"2026-07-26T12:00:00+09:00\"", response);
    }

    @Test
    void 오프셋이_없는_요청_시각은_거절된다() {
        assertThrows(
                JacksonException.class,
                () -> objectMapper.readValue("\"2026-07-26T12:00:00\"", Instant.class));
    }

    @Test
    void 초_단위_오프셋_요청_시각은_거절된다() {
        assertThrows(
                JacksonException.class,
                () -> objectMapper.readValue("\"2026-07-26T12:00:00+09:00:30\"", Instant.class));
    }

    @Test
    void 초가_생략된_요청_시각은_거절된다() {
        assertThrows(
                JacksonException.class,
                () -> objectMapper.readValue("\"2026-07-26T12:00+09:00\"", Instant.class));
    }

    @Test
    void 윤초_요청_시각은_직전_초로_정규화된다() throws Exception {
        Instant leapSecond = objectMapper.readValue("\"2016-12-31T23:59:60Z\"", Instant.class);

        assertEquals(Instant.parse("2016-12-31T23:59:59Z"), leapSecond);
    }

    @Test
    void 비문자열_요청_시각은_거절된다() {
        assertThrows(JacksonException.class, () -> objectMapper.readValue("123", Instant.class));
    }

    @Test
    void 실행과_데이터베이스_연결_시간대는_UTC로_명시된다() {
        assertEquals("UTC", environment.getProperty("spring.jackson.time-zone"));
        assertEquals(
                "UTC", environment.getProperty("spring.jpa.properties.hibernate.jdbc.time_zone"));
        assertEquals(
                "SET TIME ZONE 'UTC'",
                environment.getProperty("spring.datasource.hikari.connection-init-sql"));
    }

    private static final class CurrentTime {

        private final Clock clock;

        private CurrentTime(Clock clock) {
            this.clock = clock;
        }

        private Instant now() {
            return Instant.now(clock);
        }
    }
}
