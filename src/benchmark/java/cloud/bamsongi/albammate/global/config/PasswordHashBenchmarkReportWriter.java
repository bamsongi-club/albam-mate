package cloud.bamsongi.albammate.global.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** benchmark 결과를 stdout과 보고서 파일에 안전하게 기록한다. */
public final class PasswordHashBenchmarkReportWriter {

    private final ObjectMapper objectMapper;

    public PasswordHashBenchmarkReportWriter() {
        objectMapper = new ObjectMapper();
    }

    public String toJson(PasswordHashBenchmarkReport report) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        } catch (JacksonException exception) {
            throw new IllegalStateException("could not serialize benchmark report", exception);
        }
    }

    public void write(PasswordHashBenchmarkReport report, Path output) throws IOException {
        Path absoluteOutput = output.toAbsolutePath().normalize();
        Path parent = absoluteOutput.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(absoluteOutput, toJson(report));
    }
}
