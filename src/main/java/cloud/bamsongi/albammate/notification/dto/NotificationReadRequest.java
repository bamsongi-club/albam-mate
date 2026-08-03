package cloud.bamsongi.albammate.notification.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

/** 읽지 않음으로 되돌리는 변경 없이 읽음을 명시하는 HTTP 요청이다. */
public record NotificationReadRequest(@NotNull @AssertTrue Boolean read, boolean onlyReadPropertyProvided) {

	@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
	public static NotificationReadRequest from(JsonNode requestBody) {
		if (requestBody == null || !requestBody.isObject()) {
			return new NotificationReadRequest(null, false);
		}
		JsonNode readValue = requestBody.get("read");
		boolean onlyReadPropertyProvided = requestBody.size() == 1 && readValue != null;
		Boolean read = readValue != null && readValue.isBoolean() ? readValue.booleanValue() : null;
		return new NotificationReadRequest(read, onlyReadPropertyProvided);
	}

	@AssertTrue @JsonIgnore
	public boolean hasOnlyReadProperty() {
		return onlyReadPropertyProvided;
	}
}
