package cloud.bamsongi.albammate.room.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "rooms")
public class Room {

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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "room", fetch = FetchType.LAZY)
    private Set<Participation> participations = new HashSet<>();

    protected Room() {}
}
