package cr.attendance.admin;

import com.fasterxml.jackson.annotation.JsonIgnore;
import cr.attendance.common.Role;
import cr.attendance.common.TextRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Request bodies of the 4b management endpoints. Explicit records: nothing is bound to an entity, and unknown
 * properties (companyId, pinHash, tokenVersion, active, version, code on update...) are ignored without effect.
 * Free text goes through TextRules in the compact constructor, before Bean Validation, so lengths are measured
 * after NFC and strip. Every violation is the existing no-echo 400 INVALID_REQUEST.
 */
public final class ManagementRequests {

    static final String EMPLOYEE_CODE = "^[A-Z0-9-]{1,20}$";
    static final String HH_MM = "^([01]\\d|2[0-3]):[0-5]\\d$";

    private ManagementRequests() {
    }

    private static boolean finiteLatitude(Double v) {
        return v == null || (Double.isFinite(v) && v >= -90 && v <= 90);
    }

    private static boolean finiteLongitude(Double v) {
        return v == null || (Double.isFinite(v) && v >= -180 && v <= 180);
    }

    // Jackson coerces the JSON string "NaN" to Double.NaN, so finiteness is checked explicitly.
    public record BranchCreate(
            @NotNull @Size(min = 1, max = 120) @Pattern(regexp = TextRules.SAFE_TEXT) String name,
            @NotNull Double latitude,
            @NotNull Double longitude,
            @NotNull @Min(10) @Max(5000) Integer radiusMeters) {

        public BranchCreate {
            name = TextRules.clean(name);
        }

        @JsonIgnore
        @AssertTrue
        public boolean isLocationInRange() {
            return finiteLatitude(latitude) && finiteLongitude(longitude);
        }
    }

    public record BranchUpdate(
            @NotNull @Size(min = 1, max = 120) @Pattern(regexp = TextRules.SAFE_TEXT) String name,
            @NotNull Double latitude,
            @NotNull Double longitude,
            @NotNull @Min(10) @Max(5000) Integer radiusMeters,
            @NotNull Integer expectedVersion) {

        public BranchUpdate {
            name = TextRules.clean(name);
        }

        @JsonIgnore
        @AssertTrue
        public boolean isLocationInRange() {
            return finiteLatitude(latitude) && finiteLongitude(longitude);
        }
    }

    /** Activate / deactivate of a branch or an employee. */
    public record VersionOnly(@NotNull Integer expectedVersion) {
    }

    public record EmployeeCreate(
            @NotNull @Pattern(regexp = EMPLOYEE_CODE) String code,
            @NotNull @Size(min = 1, max = 120) @Pattern(regexp = TextRules.SAFE_TEXT) String name,
            @Size(max = 80) @Pattern(regexp = TextRules.SAFE_TEXT) String jobTitle,
            @NotNull Role role,
            Long branchId) {

        public EmployeeCreate {
            code = code == null ? null : code.strip().toUpperCase(Locale.ROOT);
            name = TextRules.clean(name);
            jobTitle = TextRules.cleanOptional(jobTitle);
        }
    }

    /** The code is absent on purpose: it is immutable. */
    public record EmployeeUpdate(
            @NotNull @Size(min = 1, max = 120) @Pattern(regexp = TextRules.SAFE_TEXT) String name,
            @Size(max = 80) @Pattern(regexp = TextRules.SAFE_TEXT) String jobTitle,
            @NotNull Role role,
            Long branchId,
            @NotNull Integer expectedVersion) {

        public EmployeeUpdate {
            name = TextRules.clean(name);
            jobTitle = TextRules.cleanOptional(jobTitle);
        }
    }

    /**
     * Optional body of a PIN reset. A note needs a reason code. The note refuses runs of 4 or more digits
     * (a PIN, an id number, a phone) and invisible characters; the UI tells the admin not to type personal data.
     */
    public record PinReset(
            PinResetReason reasonCode,
            @Size(max = 200) @Pattern(regexp = TextRules.SAFE_TEXT) String note) {

        public PinReset {
            note = TextRules.cleanOptional(note);
        }

        @JsonIgnore
        @AssertTrue
        public boolean isNoteWithoutDigitRun() {
            return !TextRules.hasDigitRun(note);
        }

        @JsonIgnore
        @AssertTrue
        public boolean isNoteBackedByReason() {
            return note == null || reasonCode != null;
        }
    }

    /** One day of the weekly schedule; times are HH:mm, an end before the start means an overnight shift. */
    public record ScheduleDay(
            @NotNull @Min(1) @Max(7) Integer dayOfWeek,
            @NotNull @Pattern(regexp = HH_MM) String startTime,
            @NotNull @Pattern(regexp = HH_MM) String endTime,
            @NotNull @Min(0) @Max(240) Integer graceMinutes) {

        @JsonIgnore
        @AssertTrue
        public boolean isEndDifferentFromStart() {
            return startTime == null || endTime == null || !startTime.equals(endTime);
        }
    }

    /** expected = the week the editor loaded (compared under the employee lock); days = the whole new week. */
    public record ScheduleReplace(
            @NotNull @Size(max = 7) List<@NotNull @Valid ScheduleDay> expected,
            @NotNull @Size(max = 7) List<@NotNull @Valid ScheduleDay> days) {

        @JsonIgnore
        @AssertTrue
        public boolean isEveryDayAtMostOnce() {
            return uniqueDays(expected) && uniqueDays(days);
        }

        private static boolean uniqueDays(List<ScheduleDay> list) {
            if (list == null) {
                return true;
            }
            HashSet<Integer> seen = new HashSet<>();
            for (ScheduleDay day : list) {
                if (day != null && day.dayOfWeek() != null && !seen.add(day.dayOfWeek())) {
                    return false;
                }
            }
            return true;
        }
    }
}
