package cloud.bamsongi.albammate.room.entity;

import cloud.bamsongi.albammate.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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
}
