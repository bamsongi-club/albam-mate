package cloud.bamsongi.albammate.monitoring;

import java.util.Locale;
import java.util.Set;

import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

import ch.qos.logback.classic.spi.ILoggingEvent;

/** 중앙 sink에 문서화된 구조화 field만 직렬화한다. */
public final class MonitoringStructuredLoggingCustomizer
	implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {

	private static final Set<String> ALLOWED_KEYS = Set.of(
		"@timestamp", "level", "event", "environment", "stackid", "service", "role", "instanceid", "release",
		"requestid", "failurecode", "reasoncode", "exceptionclass", "exceptiontype", "eventtype", "targettype",
		"action", "outcome", "roomstatus", "usecase", "section", "lockname", "measurementtime", "occurredat",
		"outboxrecordedat", "notificationrecordedat", "nextavailableat", "roomid", "messageid", "sourceeventid",
		"attempt", "batchnumber", "claimedcount", "processedcount", "retryscheduledcount", "failedcount",
		"recipientcount", "failurecount", "totalfailurecount", "reprocesscount", "deletedcount", "changedcount",
		"purgedroomcount", "deletedmessagecount", "durationms", "oldestprocessableagems", "deliverydelayms",
		"processingdurationms", "maxrundurationms", "lockatmostforms", "maximumdelayms", "warningthresholdms",
		"retrydelaymillis", "maxlocksectionsperrun", "candidatelimit", "maxbatchesperrun", "thresholdms");

	@Override
	public void customize(Members<ILoggingEvent> members) {
		members.applyingPathFilter(path -> !ALLOWED_KEYS.contains(path.name().toLowerCase(Locale.ROOT)));
	}

}
