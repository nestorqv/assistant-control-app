package cr.attendance.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.seed")
public record SeedProperties(String pin) {

    // Never print the PIN, even by accident.
    @Override
    public String toString() {
        return "SeedProperties[pin=***]";
    }
}
