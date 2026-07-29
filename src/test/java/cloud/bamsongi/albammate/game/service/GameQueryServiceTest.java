package cloud.bamsongi.albammate.game.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class GameQueryServiceTest {

    @Test
    void 조회_서비스는_읽기_전용_트랜잭션을_사용한다() {
        Transactional transactional = GameQueryService.class.getAnnotation(Transactional.class);

        assertNotNull(transactional);
        assertTrue(transactional.readOnly());
    }
}
