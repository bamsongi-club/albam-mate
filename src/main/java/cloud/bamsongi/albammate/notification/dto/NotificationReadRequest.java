package cloud.bamsongi.albammate.notification.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** 읽지 않음으로 되돌리는 변경 없이 읽음을 명시하는 HTTP 요청이다. */
@JsonDeserialize(using = NotificationReadRequest.StrictReadRequestDeserializer.class)
public record NotificationReadRequest(@NotNull @AssertTrue Boolean read, boolean onlyReadPropertyProvided) {

	@AssertTrue @JsonIgnore
	public boolean hasOnlyReadProperty() {
		return onlyReadPropertyProvided;
	}

	/** JSON token을 직접 순회해 허용하지 않은 필드와 중복 read 키를 거절한다. */
	public static final class StrictReadRequestDeserializer extends ValueDeserializer<NotificationReadRequest> {

		@Override
		public NotificationReadRequest deserialize(JsonParser parser, DeserializationContext context)
			throws JacksonException {
			if (!parser.hasToken(JsonToken.START_OBJECT)) {
				return new NotificationReadRequest(null, false);
			}

			Boolean read = null;
			boolean readProvided = false;
			boolean onlyReadPropertyProvided = true;
			while (parser.nextToken() != JsonToken.END_OBJECT) {
				if (!parser.hasToken(JsonToken.PROPERTY_NAME)) {
					onlyReadPropertyProvided = false;
					continue;
				}
				String propertyName = parser.currentName();
				JsonToken valueToken = parser.nextToken();
				if (!"read".equals(propertyName) || readProvided) {
					onlyReadPropertyProvided = false;
					parser.skipChildren();
					continue;
				}
				readProvided = true;
				if (valueToken == JsonToken.VALUE_TRUE) {
					read = true;
				} else if (valueToken == JsonToken.VALUE_FALSE) {
					read = false;
				} else {
					parser.skipChildren();
				}
			}
			return new NotificationReadRequest(read, readProvided && onlyReadPropertyProvided);
		}
	}
}
