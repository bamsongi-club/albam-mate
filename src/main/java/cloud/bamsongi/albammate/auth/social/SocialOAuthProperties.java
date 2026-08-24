package cloud.bamsongi.albammate.auth.social;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import cloud.bamsongi.albammate.user.contract.SocialProvider;

/**
 * 제공자별 OAuth client 자격증명이다.
 *
 * <p>Client ID와 Client Secret이 모두 있는 제공자만 활성화한다. 값을 주입하지 않은 제공자는 빈 문자열로 남아 등록되지 않으며 애플리케이션
 * 기동을 막지 않는다.
 */
@ConfigurationProperties(prefix = "app.social")
public class SocialOAuthProperties {

	private final Map<SocialProvider, Credentials> providers = new EnumMap<>(SocialProvider.class);

	public Map<SocialProvider, Credentials> getProviders() {
		return providers;
	}

	/** 한 제공자의 OAuth client 자격증명이다. */
	public static class Credentials {

		private String clientId = "";

		private String clientSecret = "";

		public String getClientId() {
			return clientId;
		}

		public void setClientId(String clientId) {
			this.clientId = clientId;
		}

		public String getClientSecret() {
			return clientSecret;
		}

		public void setClientSecret(String clientSecret) {
			this.clientSecret = clientSecret;
		}

		public boolean isConfigured() {
			return !clientId.isBlank() && !clientSecret.isBlank();
		}
	}
}
