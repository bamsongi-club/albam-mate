package cloud.bamsongi.albammate.room.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Version;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class Fnd03PersistenceTest {

    private static final String BASE_PACKAGE = "cloud.bamsongi.albammate.";

    @Autowired private EntityManagerFactory entityManagerFactory;

    @Autowired private Environment environment;

    @Autowired private Flyway flyway;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void 네_엔티티가_정해진_패키지에서_JPA_관리되고_Long_IDENTITY를_사용한다() {
        Map<String, String> entityTables =
                Map.of(
                        "user.entity.User", "users",
                        "game.entity.Game", "games",
                        "room.entity.Room", "rooms",
                        "room.entity.Participation", "participations");

        entityTables.forEach(
                (className, tableName) -> {
                    Class<?> entityType = loadClass(className);
                    assertTrue(entityType.isAnnotationPresent(Entity.class));
                    assertEquals(
                            tableName,
                            entityType.getAnnotation(jakarta.persistence.Table.class).name());
                    assertNotNull(
                            entityManagerFactory.getMetamodel().managedType(entityType),
                            className + " must be managed by JPA");

                    Field id = field(entityType, "id");
                    assertEquals(Long.class, id.getType());
                    assertTrue(id.isAnnotationPresent(Id.class));
                    assertEquals(
                            GenerationType.IDENTITY,
                            id.getAnnotation(GeneratedValue.class).strategy());
                    assertColumn(id, "id");
                });
    }

    @Test
    void 네_IDENTITY_테이블은_ID를_생략하면_Long_ID를_생성한다() {
        Long userId = insertUserWithoutId("identity-user@example.com");
        Long gameId = insertGameWithoutId(9002L);
        Long roomId = insertRoomWithoutId(gameId, userId);
        Long participationId = insertParticipationWithoutId(roomId, userId);

        assertNotNull(userId);
        assertNotNull(gameId);
        assertNotNull(roomId);
        assertNotNull(participationId);
    }

    @Test
    void 시각_필드는_Instant이고_물리_컬럼은_명시된_snake_case다() {
        assertInstantColumn("user.entity.User", "createdAt", "created_at");
        assertInstantColumn("user.entity.User", "updatedAt", "updated_at");
        assertInstantColumn("game.entity.Game", "createdAt", "created_at");
        assertInstantColumn("room.entity.Room", "startAt", "start_at");
        assertInstantColumn("room.entity.Room", "createdAt", "created_at");
        assertInstantColumn("room.entity.Room", "updatedAt", "updated_at");
        assertInstantColumn("room.entity.Participation", "joinedAt", "joined_at");
        assertInstantColumn("room.entity.Participation", "canceledAt", "canceled_at");

        Field version = field(loadClass("room.entity.Room"), "version");
        assertEquals(Long.class, version.getType());
        assertTrue(version.isAnnotationPresent(Version.class));
        assertColumn(version, "version");
    }

    @Test
    void room은_다른_모듈_Entity_대신_ID를_보유하고_room_내부_참가관계만_연결한다() {
        Class<?> room = loadClass("room.entity.Room");
        Class<?> participation = loadClass("room.entity.Participation");

        assertScalarId(room, "gameId", "game_id", true);
        assertScalarId(room, "hostUserId", "host_user_id", false);
        assertScalarId(participation, "userId", "user_id", false);
        assertNoExternalEntityField(room);
        assertNoExternalEntityField(participation);

        assertStringEnum(room, "roomType", "room_type");
        assertStringEnum(room, "experienceLevel", "experience_level");
        assertStringEnum(room, "status", "status");
        assertStringEnum(participation, "status", "status");

        assertAssociation(participation, "room", "room_id", false);

        Field participations = field(room, "participations");
        OneToMany oneToMany = participations.getAnnotation(OneToMany.class);
        assertNotNull(oneToMany);
        assertEquals("room", oneToMany.mappedBy());
    }

    @Test
    void Room의_새_인스턴스는_지역을_홍대로_초기화한다() throws IllegalAccessException {
        Room room = new Room();
        Field region = field(Room.class, "region");
        region.setAccessible(true);

        assertEquals("홍대", region.get(room));
    }

    @Test
    void Hibernate는_validate이고_Flyway_V1과_네_테이블이_적용된다() {
        assertEquals("validate", environment.getProperty("spring.jpa.hibernate.ddl-auto"));

        flyway.migrate();
        flyway.validate();

        Integer applied =
                jdbcTemplate.queryForObject(
                        "select count(*) from flyway_schema_history where version = '1'",
                        Integer.class);
        assertEquals(1, applied);

        for (String tableName : Set.of("users", "games", "rooms", "participations")) {
            Integer tableCount =
                    jdbcTemplate.queryForObject(
                            "select count(*) from information_schema.tables "
                                    + "where upper(table_schema) = 'PUBLIC' "
                                    + "and upper(table_name) = upper(?)",
                            Integer.class,
                            tableName);
            assertEquals(1, tableCount, tableName + " must be created by V1");
        }
    }

    @Test
    void ERD_핵심_DB_제약은_H2_Flyway_스키마에서_위반을_거절한다() {
        insertUser(101L, "constraint-user@example.com");
        insertUser(102L, "participant-user@example.com");
        insertGame(201L, 9001L);
        insertRoom(301L, 201L, 101L, 2, 0);
        insertParticipation(401L, 301L, 102L, "ACTIVE", null);

        assertEquals(
                "홍대",
                jdbcTemplate.queryForObject(
                        "select region from rooms where id = 301", String.class));
        assertEquals(
                0,
                jdbcTemplate.queryForObject(
                        "select active_participant_count from rooms where id = 301",
                        Integer.class));
        assertEquals(
                0L,
                jdbcTemplate.queryForObject(
                        "select version from rooms where id = 301", Long.class));

        assertConstraintViolation(() -> insertUser(103L, "constraint-user@example.com"));
        assertConstraintViolation(() -> insertGame(202L, 9001L));
        assertConstraintViolation(() -> insertRoom(302L, null, 101L, 2, 0));
        assertConstraintViolation(() -> insertRoom(303L, 201L, 101L, 0, 0));
        assertConstraintViolation(() -> insertRoom(304L, 201L, 101L, 11, 0));
        assertConstraintViolation(() -> insertRoom(305L, 201L, 101L, 2, -1));
        assertConstraintViolation(() -> insertRoom(306L, 201L, 101L, 2, 3));
        assertConstraintViolation(() -> insertParticipation(402L, 301L, 102L, "ACTIVE", null));
        assertConstraintViolation(
                () -> insertParticipation(403L, 301L, 101L, "ACTIVE", "2026-07-26T04:00:00Z"));
        assertConstraintViolation(() -> insertParticipation(404L, 301L, 101L, "CANCELED", null));

        assertConstraintViolation(() -> insertRoom(307L, 999_999L, 101L, 2, 0));
        assertConstraintViolation(() -> insertRoom(308L, 201L, 999_999L, 2, 0));
        assertConstraintViolation(() -> insertParticipation(405L, 999_999L, 102L, "ACTIVE", null));
        assertConstraintViolation(() -> insertParticipation(406L, 301L, 999_999L, "ACTIVE", null));
    }

    private void assertInstantColumn(String className, String fieldName, String columnName) {
        Field field = field(loadClass(className), fieldName);
        assertEquals(Instant.class, field.getType());
        assertColumn(field, columnName);
    }

    private void assertStringEnum(Class<?> entityType, String fieldName, String columnName) {
        Field field = field(entityType, fieldName);
        assertTrue(field.getType().isEnum());
        assertEquals(EnumType.STRING, field.getAnnotation(Enumerated.class).value());
        assertColumn(field, columnName);
    }

    private void assertAssociation(
            Class<?> entityType, String fieldName, String columnName, boolean nullable) {
        Field field = field(entityType, fieldName);
        assertTrue(field.isAnnotationPresent(ManyToOne.class));
        JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
        assertNotNull(joinColumn);
        assertEquals(columnName, joinColumn.name());
        assertEquals(nullable, joinColumn.nullable());
    }

    private void assertColumn(Field field, String columnName) {
        Column column = field.getAnnotation(Column.class);
        assertNotNull(column, field.getName() + " must declare @Column");
        assertEquals(columnName, column.name());
    }

    private void assertScalarId(
            Class<?> entityType, String fieldName, String columnName, boolean nullable) {
        Field field = field(entityType, fieldName);
        assertEquals(Long.class, field.getType());
        Column column = field.getAnnotation(Column.class);
        assertNotNull(column);
        assertEquals(columnName, column.name());
        assertEquals(nullable, column.nullable());
    }

    private void assertNoExternalEntityField(Class<?> entityType) {
        assertTrue(
                Arrays.stream(entityType.getDeclaredFields())
                        .map(Field::getType)
                        .map(Class::getName)
                        .noneMatch(
                                typeName ->
                                        typeName.equals("cloud.bamsongi.albammate.game.entity.Game")
                                                || typeName.equals(
                                                        "cloud.bamsongi.albammate.user.entity.User")));
    }

    private void assertConstraintViolation(Runnable operation) {
        assertThrows(DataAccessException.class, operation::run);
    }

    private void insertUser(long id, String email) {
        jdbcTemplate.update(
                "insert into users "
                        + "(id, email, password_hash, nickname, created_at, updated_at) "
                        + "values (?, ?, 'test-hash', '테스트 사용자', "
                        + "TIMESTAMP WITH TIME ZONE '2026-07-26T03:00:00Z', "
                        + "TIMESTAMP WITH TIME ZONE '2026-07-26T03:00:00Z')",
                id,
                email);
    }

    private Long insertUserWithoutId(String email) {
        jdbcTemplate.update(
                "insert into users "
                        + "(email, password_hash, nickname, created_at, updated_at) "
                        + "values (?, 'identity-test-hash', 'IDENTITY 테스트 사용자', "
                        + "TIMESTAMP WITH TIME ZONE '2026-07-26T03:00:00Z', "
                        + "TIMESTAMP WITH TIME ZONE '2026-07-26T03:00:00Z')",
                email);
        return jdbcTemplate.queryForObject(
                "select id from users where email = ?", Long.class, email);
    }

    private void insertGame(long id, long bggId) {
        jdbcTemplate.update(
                "insert into games "
                        + "(id, bgg_id, name, english_name, recommended_player_count, tag, "
                        + "estimated_play_time, description, detail_description, created_at) "
                        + "values (?, ?, '테스트 게임', 'Test Game', '2~4명', '전략', '60분', "
                        + "'간단 설명', '상세 설명', TIMESTAMP WITH TIME ZONE '2026-07-26T03:00:00Z')",
                id,
                bggId);
    }

    private Long insertGameWithoutId(long bggId) {
        jdbcTemplate.update(
                "insert into games "
                        + "(bgg_id, name, english_name, recommended_player_count, tag, "
                        + "estimated_play_time, description, detail_description, created_at) "
                        + "values (?, 'IDENTITY 테스트 게임', 'Identity Test Game', '2~4명', '전략', '60분', "
                        + "'간단 설명', '상세 설명', TIMESTAMP WITH TIME ZONE '2026-07-26T03:00:00Z')",
                bggId);
        return jdbcTemplate.queryForObject(
                "select id from games where bgg_id = ?", Long.class, bggId);
    }

    private void insertRoom(Long id, Long gameId, long hostUserId, int capacity, int activeCount) {
        jdbcTemplate.update(
                "insert into rooms "
                        + "(id, game_id, host_user_id, room_type, title, experience_level, "
                        + "is_rulemaster_led, capacity, active_participant_count, start_at, place, "
                        + "status, created_at, updated_at) "
                        + "values (?, ?, ?, 'GAME_FOCUSED', '제약 테스트 방', 'ALL_LEVELS', true, ?, ?, "
                        + "TIMESTAMP WITH TIME ZONE '2026-07-27T03:00:00Z', '테스트 장소', 'RECRUITING', "
                        + "TIMESTAMP WITH TIME ZONE '2026-07-26T03:00:00Z', "
                        + "TIMESTAMP WITH TIME ZONE '2026-07-26T03:00:00Z')",
                id,
                gameId,
                hostUserId,
                capacity,
                activeCount);
    }

    private Long insertRoomWithoutId(long gameId, long hostUserId) {
        String title = "IDENTITY 자동 생성 테스트 방";
        jdbcTemplate.update(
                "insert into rooms "
                        + "(game_id, host_user_id, room_type, title, experience_level, "
                        + "is_rulemaster_led, capacity, active_participant_count, start_at, place, "
                        + "status, created_at, updated_at) "
                        + "values (?, ?, 'GAME_FOCUSED', ?, 'ALL_LEVELS', true, 2, 0, "
                        + "TIMESTAMP WITH TIME ZONE '2026-07-27T03:00:00Z', '테스트 장소', 'RECRUITING', "
                        + "TIMESTAMP WITH TIME ZONE '2026-07-26T03:00:00Z', "
                        + "TIMESTAMP WITH TIME ZONE '2026-07-26T03:00:00Z')",
                gameId,
                hostUserId,
                title);
        return jdbcTemplate.queryForObject(
                "select id from rooms where game_id = ? and host_user_id = ? and title = ?",
                Long.class,
                gameId,
                hostUserId,
                title);
    }

    private void insertParticipation(
            long id, long roomId, long userId, String status, String canceledAt) {
        String canceledAtSql =
                canceledAt == null ? "NULL" : "TIMESTAMP WITH TIME ZONE '" + canceledAt + "'";
        jdbcTemplate.update(
                "insert into participations "
                        + "(id, room_id, user_id, status, joined_at, canceled_at) "
                        + "values (?, ?, ?, ?, TIMESTAMP WITH TIME ZONE '2026-07-26T03:00:00Z', "
                        + canceledAtSql
                        + ")",
                id,
                roomId,
                userId,
                status);
    }

    private Long insertParticipationWithoutId(long roomId, long userId) {
        jdbcTemplate.update(
                "insert into participations "
                        + "(room_id, user_id, status, joined_at, canceled_at) "
                        + "values (?, ?, 'ACTIVE', TIMESTAMP WITH TIME ZONE '2026-07-26T03:00:00Z', NULL)",
                roomId,
                userId);
        return jdbcTemplate.queryForObject(
                "select id from participations where room_id = ? and user_id = ?",
                Long.class,
                roomId,
                userId);
    }

    private Class<?> loadClass(String relativeClassName) {
        try {
            return Class.forName(BASE_PACKAGE + relativeClassName);
        } catch (ClassNotFoundException exception) {
            fail("missing entity: " + BASE_PACKAGE + relativeClassName);
            return Object.class;
        }
    }

    private Field field(Class<?> entityType, String fieldName) {
        try {
            return entityType.getDeclaredField(fieldName);
        } catch (NoSuchFieldException exception) {
            fail(entityType.getName() + " must declare " + fieldName);
            return null;
        }
    }
}
