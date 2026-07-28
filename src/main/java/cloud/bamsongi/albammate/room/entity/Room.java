package cloud.bamsongi.albammate.room.entity;

import cloud.bamsongi.albammate.global.entity.BaseEntity;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "rooms")
public class Room extends BaseEntity {

    public static final Duration AUTOMATIC_FINISH_AFTER_START = Duration.ofHours(24);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "game_id")
    private Long gameId;

    @Column(name = "host_user_id", nullable = false)
    private Long hostUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", nullable = false, length = 20)
    private RoomType roomType;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "description", length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "experience_level", nullable = false, length = 30)
    private ExperienceLevel experienceLevel;

    @Column(name = "is_rulemaster_led", nullable = false)
    private boolean rulemasterLed;

    @Column(name = "region", nullable = false, length = 50)
    private String region = "홍대";

    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Column(name = "active_participant_count", nullable = false)
    private int activeParticipantCount;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "place", nullable = false, length = 100)
    private String place;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RoomStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * 모집 중인 새 방을 만든다.
     *
     * <p>개설자는 {@code PARTICIPATIONS}에 저장하지 않으므로 참가 카운터는 0으로 시작하고, 화면의 최초 참가자 정보는 방 응답 조립 단계에서 주최자를
     * 포함해 계산한다.
     */
    public static Room create(
            Long hostUserId,
            RoomType roomType,
            String title,
            String description,
            Long gameId,
            ExperienceLevel experienceLevel,
            boolean rulemasterLed,
            Instant startAt,
            String place,
            int capacity) {
        Room room = new Room();
        room.hostUserId = Objects.requireNonNull(hostUserId, "hostUserId");
        room.roomType = Objects.requireNonNull(roomType, "roomType");
        room.title = Objects.requireNonNull(title, "title");
        room.description = description;
        room.gameId = gameId;
        room.experienceLevel = Objects.requireNonNull(experienceLevel, "experienceLevel");
        room.rulemasterLed = rulemasterLed;
        room.region = "홍대";
        room.capacity = capacity;
        room.activeParticipantCount = 0;
        room.startAt = Objects.requireNonNull(startAt, "startAt");
        room.place = Objects.requireNonNull(place, "place");
        room.status = RoomStatus.RECRUITING;
        return room;
    }

    /**
     * 요청 기준 시각에 맞춰 시간 기반 상태를 순서대로 보정한다.
     *
     * <p>두 경계를 모두 지난 {@code RECRUITING} 방은 한 번의 보정에서 {@code CLOSED}를 거쳐 {@code FINISHED}까지 전이한다. 최종
     * 상태는 덮어쓰지 않으며, 실제 전이가 있었는지만 반환한다.
     */
    public boolean reconcileStateAt(Instant requestTime) {
        Objects.requireNonNull(requestTime, "requestTime");
        boolean changed = false;

        if (status == RoomStatus.RECRUITING && !requestTime.isBefore(startAt)) {
            status = RoomStatus.CLOSED;
            changed = true;
        }
        if (status == RoomStatus.CLOSED
                && !requestTime.isBefore(startAt.plus(AUTOMATIC_FINISH_AFTER_START))) {
            status = RoomStatus.FINISHED;
            changed = true;
        }
        return changed;
    }

    /** 참가 관계가 활성화됐을 때 점유 인원을 반영하고, 마지막 좌석이면 모집을 닫는다. */
    public void addActiveParticipant() {
        if (activeParticipantCount >= capacity) {
            throw new IllegalStateException("active participant count exceeds room capacity");
        }
        activeParticipantCount++;
        if (activeParticipantCount == capacity) {
            status = RoomStatus.CLOSED;
        }
    }

    /**
     * 활성 참가 관계가 취소됐을 때 점유 인원을 반영하고, 시작 전 빈자리가 생긴 닫힌 방만 다시 모집한다.
     *
     * <p>최종 상태인 {@code CANCELED}, {@code FINISHED}는 참가 관계의 이력 변경으로 덮어쓰지 않는다.
     */
    public void removeActiveParticipant() {
        if (activeParticipantCount <= 0) {
            throw new IllegalStateException("active participant count cannot be negative");
        }
        activeParticipantCount--;
        if (status == RoomStatus.CLOSED) {
            status = RoomStatus.RECRUITING;
        }
    }

    /** 수정 가능 조건을 통과한 방의 허용 필드만 새 값으로 반영한다. */
    public void update(
            String title,
            String description,
            Long gameId,
            ExperienceLevel experienceLevel,
            boolean rulemasterLed,
            Instant startAt,
            String place,
            int capacity) {
        this.title = Objects.requireNonNull(title, "title");
        this.description = description;
        this.gameId = gameId;
        this.experienceLevel = Objects.requireNonNull(experienceLevel, "experienceLevel");
        this.rulemasterLed = rulemasterLed;
        this.startAt = Objects.requireNonNull(startAt, "startAt");
        this.place = Objects.requireNonNull(place, "place");
        this.capacity = capacity;
    }
}
