package cloud.bamsongi.albammate.room.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ParticipationRepositoryTest {

    private static final Instant BASE_TIME = Instant.parse("2026-07-28T00:00:00Z");

    @Autowired private ParticipationRepository participationRepository;
    @Autowired private RoomRepository roomRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long roomId;
    private long firstUserId;
    private long secondUserId;
    private long canceledUserId;

    @BeforeEach
    void setUp() {
        long hostUserId = insertUser("detail-host@example.com", "방장");
        firstUserId = insertUser("detail-first@example.com", "첫 참가자");
        secondUserId = insertUser("detail-second@example.com", "둘째 참가자");
        canceledUserId = insertUser("detail-canceled@example.com", "취소 참가자");
        Room room =
                roomRepository.saveAndFlush(
                        Room.create(
                                hostUserId,
                                RoomType.PERSON_FOCUSED,
                                "상세 조회 테스트 방",
                                null,
                                null,
                                ExperienceLevel.ALL_LEVELS,
                                false,
                                BASE_TIME.plusSeconds(3600),
                                "테스트 장소",
                                3));
        roomId = room.getId();
    }

    @Test
    void ACTIVE_참가만_joinedAt과_ID_오름차순으로_조회한다() {
        insertParticipation(secondUserId, "ACTIVE", BASE_TIME.plusSeconds(20), null);
        insertParticipation(firstUserId, "ACTIVE", BASE_TIME.plusSeconds(10), null);
        insertParticipation(
                canceledUserId, "CANCELED", BASE_TIME.plusSeconds(5), BASE_TIME.plusSeconds(6));

        List<Long> userIds =
                participationRepository
                        .findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(
                                roomId, ParticipationStatus.ACTIVE)
                        .stream()
                        .map(participation -> participation.getUserId())
                        .toList();

        assertEquals(List.of(firstUserId, secondUserId), userIds);
    }

    private long insertUser(String email, String nickname) {
        jdbcTemplate.update(
                "insert into users (email, password_hash, nickname, created_at, updated_at) "
                        + "values (?, 'fixture-password-hash', ?, ?, ?)",
                email,
                nickname,
                BASE_TIME,
                BASE_TIME);
        return jdbcTemplate.queryForObject(
                "select id from users where email = ?", Long.class, email);
    }

    private void insertParticipation(
            long userId, String status, Instant joinedAt, Instant canceledAt) {
        jdbcTemplate.update(
                "insert into participations "
                        + "(room_id, user_id, status, joined_at, canceled_at, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?)",
                roomId,
                userId,
                status,
                joinedAt,
                canceledAt,
                BASE_TIME,
                canceledAt == null ? BASE_TIME : canceledAt);
    }
}
