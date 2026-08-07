package cloud.bamsongi.albammate.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
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
		// 저장 파일명은 업로드마다 새 UUID라 재사용되지 않지만, 삭제된 이미지는 고정 기간 캐시로 두면 이미
		// 그 URL을 받아본 브라우저가 만료 전까지 원본이 지워진 뒤에도 개인정보 이미지를 계속 보여줄 수 있다.
		// no-cache는 매 요청 조건부 GET으로 서버에 재검증시켜, 파일이 사라지면 캐시 대신 바로 404를 받게 한다.
		registry.addResourceHandler("/uploads/profile/**")
			.addResourceLocations(location)
			.setCacheControl(CacheControl.noCache().mustRevalidate());
	}
}
