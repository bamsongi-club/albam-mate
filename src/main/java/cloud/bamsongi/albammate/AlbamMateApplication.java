package cloud.bamsongi.albammate;

import cloud.bamsongi.albammate.global.time.UtcTimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AlbamMateApplication {

    public static void main(String[] args) {
        UtcTimeZone.configure();
        SpringApplication.run(AlbamMateApplication.class, args);
    }
}
