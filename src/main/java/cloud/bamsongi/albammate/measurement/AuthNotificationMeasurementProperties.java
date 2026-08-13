package cloud.bamsongi.albammate.measurement;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 승인된 부하 측정 배포에서만 내부 계측을 켠다. */
@ConfigurationProperties("app.measurement.auth-notification")
public record AuthNotificationMeasurementProperties(boolean enabled) {
}
