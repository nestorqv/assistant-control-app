package cr.attendance.admin;

import cr.attendance.company.Branch;

public record BranchView(Long id, String name, double latitude, double longitude, int radiusMeters,
        boolean active, long activeEmployees, int version) {

    static BranchView of(Branch b, long activeEmployees) {
        return new BranchView(b.getId(), b.getName(), b.getLatitude(), b.getLongitude(), b.getRadiusMeters(),
                b.isActive(), activeEmployees, b.getVersion());
    }
}
