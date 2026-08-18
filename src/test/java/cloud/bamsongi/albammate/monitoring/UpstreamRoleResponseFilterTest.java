package cloud.bamsongi.albammate.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class UpstreamRoleResponseFilterTest {

	@Test
	void T1_정확한_app_role만_수동_응답_헤더로_설정하고_나머지는_거부한다() throws Exception {
		for (String role : List.of("app1", "app2")) {
			MockHttpServletResponse response = responseFor(role);

			assertEquals(role, response.getHeader(UpstreamRoleResponseFilter.HEADER_NAME));
		}
		for (String invalidRole : List.of("", " app1", "app1 ", "APP1", "app1:8080", "127.0.0.1:8080")) {
			assertThrows(IllegalArgumentException.class, () -> new UpstreamRoleResponseFilter(invalidRole));
		}
	}

	@Test
	void T1_local과_production은_동일한_환경_role을_수동_응답_헤더_입력으로_연결한다() throws Exception {
		String local = Files.readString(Path.of("src/main/resources/application-local.yml"));
		String production = Files.readString(Path.of("src/main/resources/application-production.yml"));

		assertTrue(local.contains("upstream-role: ${ALBAM_MATE_ROLE:app1}"));
		assertTrue(production.contains("upstream-role: ${ALBAM_MATE_ROLE}"));
	}

	private MockHttpServletResponse responseFor(String role) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		new UpstreamRoleResponseFilter(role).doFilter(new MockHttpServletRequest(), response,
			(request, servletResponse) -> {});
		return response;
	}
}
