package cloud.bamsongi.albammate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AlbamMateApplicationTest {

	@Autowired
	private DataSource dataSource;

	@Test
	void contextLoads() {}

	@Test
	void 외부_데이터소스_환경변수가_있어도_H2로_기동한다() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			assertEquals("H2", connection.getMetaData().getDatabaseProductName());
		}
	}
}
