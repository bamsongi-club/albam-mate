package cloud.bamsongi.albammate.assistant.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AssistantConsentProperties.class)
public class AssistantConsentConfiguration {}
