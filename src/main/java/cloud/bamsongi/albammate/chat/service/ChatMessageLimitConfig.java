package cloud.bamsongi.albammate.chat.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** CHAT-02 메시지 전송 요청 검증 길이 속성을 등록한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatMessageLimitProperties.class)
class ChatMessageLimitConfig {

}
