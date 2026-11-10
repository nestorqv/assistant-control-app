package cr.attendance.auth;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PinConfig {

    // Production source of PINs. Tests construct PinGenerator with a seeded Random instead.
    @Bean
    RandomGenerator pinRandom() {
        return new SecureRandom();
    }
}
