package cloud.bamsongi.albammate.testsupport;

import org.testcontainers.utility.DockerImageName;

public final class PgVectorPostgresImages {

	private static final String POSTGRES_18_IMAGE = "pgvector/pgvector:pg18";
	private static final String POSTGRES_15_IMAGE = "pgvector/pgvector:pg15";

	private PgVectorPostgresImages() {}

	public static DockerImageName postgres18() {
		return DockerImageName.parse(POSTGRES_18_IMAGE).asCompatibleSubstituteFor("postgres");
	}

	public static DockerImageName postgres15() {
		return DockerImageName.parse(POSTGRES_15_IMAGE).asCompatibleSubstituteFor("postgres");
	}
}
