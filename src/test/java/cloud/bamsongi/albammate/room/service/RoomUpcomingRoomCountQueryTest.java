package cloud.bamsongi.albammate.room.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;

import cloud.bamsongi.albammate.room.repository.RoomRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoomUpcomingRoomCountQueryTest {

    @Mock private RoomRepository roomRepository;

    @Test
    void 빈_게임_ID_목록은_저장소를_조회하지_않고_빈_맵을_반환한다() {
        RoomUpcomingRoomCountQuery query = new RoomUpcomingRoomCountQuery(roomRepository);

        assertEquals(
                Map.of(),
                query.findUpcomingRoomCounts(List.of(), Instant.parse("2026-07-28T00:00:00Z")));

        verifyNoInteractions(roomRepository);
    }
}
