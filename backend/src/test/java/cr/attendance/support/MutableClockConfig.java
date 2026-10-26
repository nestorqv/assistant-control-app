package cr.attendance.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Shared by every clock-driven Stage 2 integration test via @Import, so they all reuse one cached
 * Spring context and one embedded database.
 */
@TestConfiguration
public class MutableClockConfig {

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock();
    }
}
