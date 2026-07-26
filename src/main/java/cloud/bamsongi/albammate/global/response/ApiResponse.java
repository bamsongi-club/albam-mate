package cloud.bamsongi.albammate.global.response;

import cloud.bamsongi.albammate.global.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatusCode;

/** 모든 HTTP 응답이 공유하는 공통 응답 봉투다. */
public final class ApiResponse<T> {

    private final int status;
    private final String code;
    private final String message;
    private final T data;

    private ApiResponse(int status, String code, String message, T data) {
        this.status = status;
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> success(HttpStatusCode status, T data) {
        return success(status.value(), data);
    }

    public static <T> ApiResponse<T> success(int status, T data) {
        validateSuccessStatus(status);
        return new ApiResponse<>(status, null, null, Objects.requireNonNull(data, "data"));
    }

    /** 응답 모델이 없는 성공 결과를 계약에 맞는 빈 JSON 객체로 만든다. */
    public static ApiResponse<Map<String, Object>> success(HttpStatusCode status) {
        return success(status.value(), Map.of());
    }

    public static ApiResponse<Void> failure(ErrorCode errorCode) {
        Objects.requireNonNull(errorCode, "errorCode");
        return new ApiResponse<>(
                errorCode.getStatus(), errorCode.getCode(), errorCode.getMessage(), null);
    }

    public int getStatus() {
        return status;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getCode() {
        return code;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getMessage() {
        return message;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public T getData() {
        return data;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    public T data() {
        return data;
    }

    private static void validateSuccessStatus(int status) {
        if (status != 200 && status != 201) {
            throw new IllegalArgumentException("성공 응답 상태 코드는 200 또는 201이어야 합니다.");
        }
    }
}
