package cloud.bamsongi.albammate.monitoring;

import java.util.Locale;
import java.util.Set;

import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

import ch.qos.logback.classic.spi.ILoggingEvent;

/** 중앙 sink에 허용되지 않은 MDC key가 구조화 JSON으로 직렬화되는 것을 막는다. */
public final class MonitoringStructuredLoggingCustomizer
	implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {

	private static final Set<String> FORBIDDEN_KEYS = Set.of(
		"email", "ip", "session", "cookie", "token", "authorization", "requestbody", "responsebody",
		"querystring", "prompt", "response", "toolargs", "toolresult", "chatcontent", "notificationpayload",
		"rawsql", "userid", "actoruserid", "roomid", "messageid", "sourceeventid");

	@Override
	public void customize(Members<ILoggingEvent> members) {
		members.applyingPathFilter(path -> FORBIDDEN_KEYS.contains(path.name().toLowerCase(Locale.ROOT)));
	}

}
