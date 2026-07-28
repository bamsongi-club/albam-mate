package cloud.bamsongi.albammate.room.entity;

import cloud.bamsongi.albammate.global.entity.BaseEntity;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "participations")
public class Participation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ParticipationStatus status;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    /** 신규 참가 관계를 활성 상태로 만든다. */
    public static Participation createActive(Room room, Long userId, Instant joinedAt) {
        Participation participation = new Participation();
        participation.room = Objects.requireNonNull(room, "room");
        participation.userId = Objects.requireNonNull(userId, "userId");
        participation.status = ParticipationStatus.ACTIVE;
        participation.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt");
        participation.canceledAt = null;
        return participation;
    }

    /** 취소했던 참가 관계를 새 행 없이 다시 활성화한다. */
    public void reactivate(Instant joinedAt) {
        status = ParticipationStatus.ACTIVE;
        this.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt");
        canceledAt = null;
    }

    /** 현재 활성 참가 관계를 이력 보존 상태로 취소한다. */
    public void cancel(Instant canceledAt) {
        status = ParticipationStatus.CANCELED;
        this.canceledAt = Objects.requireNonNull(canceledAt, "canceledAt");
    }
}
