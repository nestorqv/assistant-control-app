package cr.attendance.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("app.seed")
public record SeedProperties(String pin, Double branchLatitude, Double branchLongitude,
        Integer branchRadiusMeters, Boolean demoDay) {

    // Spring Boot binds through the canonical constructor.
    @ConstructorBinding
    public SeedProperties {
    }

    public SeedProperties(String pin, Double branchLatitude, Double branchLongitude, Integer branchRadiusMeters) {
        this(pin, branchLatitude, branchLongitude, branchRadiusMeters, false);
    }

    // Never print the PIN, even by accident.
    @Override
    public String toString() {
        return "SeedProperties[pin=***, branchLocation=" + (branchLatitude != null ? "set" : "default") + "]";
    }
}
