package cloud.bamsongi.albammate.global.validation;

import java.util.Set;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;

/** HTTP 요청의 query parameter 이름이 호출자가 선언한 허용 목록 안에 있는지 검사한다. */
public final class QueryParameterAllowlist {

	private QueryParameterAllowlist() {}

	/**
	 * 허용되지 않은 query parameter 이름이 있으면 기존 HTTP 오류 계약의 VALIDATION_ERROR로
	 * {@link BusinessException}을 던진다.
	 *
	 * @param request 검사할 HTTP 요청
	 * @param allowedParameterNames null이 아닌 허용 query parameter 이름 목록
	 * @throws BusinessException 허용되지 않은 query parameter 이름이 있으면 발생
	 * @throws NullPointerException request 또는 allowedParameterNames가 null이면 발생
	 */
	public static void validate(HttpServletRequest request, Set<String> allowedParameterNames) {
		if (!allowedParameterNames.containsAll(request.getParameterMap().keySet())) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
	}
}
