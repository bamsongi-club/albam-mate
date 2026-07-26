package cloud.bamsongi.albammate.global.config;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import org.springframework.boot.jackson.JacksonComponent;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.exc.InvalidFormatException;

@JacksonComponent
public class TimeJacksonComponent {

    private static final ZoneId RESPONSE_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter OFFSET_DATE_TIME_FORMATTER =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final Pattern RFC3339_FULL_TIME =
            Pattern.compile(
                    "^\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:[0-5]\\d(?:\\.\\d+)?(?:[Zz]|[+-]\\d{2}:\\d{2})$");

    public static class InstantSerializer extends ValueSerializer<Instant> {

        @Override
        public void serialize(
                Instant value,
                JsonGenerator generator,
                tools.jackson.databind.SerializationContext context)
                throws JacksonException {
            generator.writeString(value.atZone(RESPONSE_ZONE).format(OFFSET_DATE_TIME_FORMATTER));
        }
    }

    public static class InstantDeserializer extends ValueDeserializer<Instant> {

        @Override
        public Instant deserialize(JsonParser parser, DeserializationContext context)
                throws JacksonException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (Instant) context.handleUnexpectedToken(Instant.class, parser);
            }

            String value = parser.getString();
            if (!RFC3339_FULL_TIME.matcher(value).matches()) {
                throw invalidFormat(parser, value);
            }

            try {
                String normalizedValue = value.replace('t', 'T').replace('z', 'Z');
                return OffsetDateTime.parse(normalizedValue, OFFSET_DATE_TIME_FORMATTER)
                        .toInstant();
            } catch (DateTimeParseException exception) {
                throw invalidFormat(parser, value);
            }
        }

        private static InvalidFormatException invalidFormat(JsonParser parser, String value) {
            return InvalidFormatException.from(
                    parser, "시간은 RFC 3339 오프셋 형식이어야 합니다.", value, Instant.class);
        }
    }
}
