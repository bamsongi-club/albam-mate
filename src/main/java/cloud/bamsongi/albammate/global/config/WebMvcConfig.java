package cloud.bamsongi.albammate.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 프로필 이미지 업로드 디렉토리를 정적 리소스로 서빙한다. */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

	private final String uploadDir;

	public WebMvcConfig(
		@Value("${app.profile-image.upload-dir:./uploads/profile}")
		String uploadDir) {
		this.uploadDir = uploadDir;
	}

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		String location = "file:" + java.nio.file.Path.of(uploadDir).toAbsolutePath().normalize() + "/";
		registry.addResourceHandler("/uploads/profile/**")
			.addResourceLocations(location)
			.setCachePeriod(86400);
	}
}
