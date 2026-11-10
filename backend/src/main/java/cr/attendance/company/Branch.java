package cr.attendance.company;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "branch")
public class Branch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "latitude", nullable = false)
    private Double latitude;

    @Column(name = "longitude", nullable = false)
    private Double longitude;

    @Column(name = "radius_meters", nullable = false)
    private Integer radiusMeters = 100;

    // Retired branches stay for history (entries reference them).
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected Branch() {
    }

    public Branch(Long companyId, String name, Double latitude, Double longitude, Integer radiusMeters) {
        this.companyId = companyId;
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.radiusMeters = radiusMeters;
    }

    public Long getId() {
        return id;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public String getName() {
        return name;
    }

    public Double getLatitude() {
        return latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public boolean isActive() {
        return active;
    }

    public Integer getRadiusMeters() {
        return radiusMeters;
    }

    public Integer getVersion() {
        return version;
    }

    /** Returns the names of the fields that changed (never their values); empty = no-op. */
    public List<String> update(String newName, double newLatitude, double newLongitude, int newRadiusMeters) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(name, newName)) {
            changed.add("name");
        }
        if (Double.compare(latitude, newLatitude) != 0) {
            changed.add("latitude");
        }
        if (Double.compare(longitude, newLongitude) != 0) {
            changed.add("longitude");
        }
        if (radiusMeters != newRadiusMeters) {
            changed.add("radiusMeters");
        }
        name = newName;
        latitude = newLatitude;
        longitude = newLongitude;
        radiusMeters = newRadiusMeters;
        return changed;
    }

    public boolean activate() {
        boolean changed = !active;
        active = true;
        return changed;
    }

    public boolean deactivate() {
        boolean changed = active;
        active = false;
        return changed;
    }
}
