package cloud.bamsongi.albammate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import cloud.bamsongi.albammate.global.time.UtcTimeZone;

@SpringBootApplication
public class AlbamMateApplication {

	public static void main(String[] args) {
		UtcTimeZone.configure();
		SpringApplication.run(AlbamMateApplication.class, args);
	}
}
