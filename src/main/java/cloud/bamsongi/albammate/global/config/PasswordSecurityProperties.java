package cloud.bamsongi.albammate.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 비밀번호 저장 알고리즘의 운영 설정이다. */
@ConfigurationProperties(prefix = "app.security.password")
public class PasswordSecurityProperties {

    private int bcryptCost = 10;

    public int getBcryptCost() {
        return bcryptCost;
    }

    public void setBcryptCost(int bcryptCost) {
        this.bcryptCost = bcryptCost;
    }

    public void validate() {
        if (bcryptCost < 10 || bcryptCost > 31) {
            throw new IllegalArgumentException("bcrypt cost must be between 10 and 31");
        }
    }
}
