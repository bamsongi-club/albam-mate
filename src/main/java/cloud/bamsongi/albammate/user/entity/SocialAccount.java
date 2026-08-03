package cloud.bamsongi.albammate.user.entity;

import java.util.Objects;

import cloud.bamsongi.albammate.global.entity.BaseEntity;
import cloud.bamsongi.albammate.user.contract.SocialProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 제공자가 보장하는 외부 subject와 알밤메이트 사용자 사이의 영속 연결이다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
	name = "social_accounts",
	uniqueConstraints = {
		@UniqueConstraint(
			name = "uq_social_accounts_provider_subject",
			columnNames = {"provider", "provider_subject"}),
		@UniqueConstraint(
			name = "uq_social_accounts_user_provider",
			columnNames = {"user_id", "provider"})
	})
public class SocialAccount extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
		name = "user_id",
		nullable = false,
		foreignKey = @ForeignKey(name = "fk_social_accounts_user"))
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(name = "provider", nullable = false, length = 20)
	private SocialProvider provider;

	@Column(name = "provider_subject", nullable = false, length = 255)
	private String providerSubject;

	private SocialAccount(User user, SocialProvider provider, String providerSubject) {
		this.user = Objects.requireNonNull(user, "user");
		this.provider = Objects.requireNonNull(provider, "provider");
		this.providerSubject = Objects.requireNonNull(providerSubject, "providerSubject");
	}

	public static SocialAccount create(User user, SocialProvider provider, String providerSubject) {
		return new SocialAccount(user, provider, providerSubject);
	}
}
