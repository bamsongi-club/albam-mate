package cloud.bamsongi.albammate.global.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
