package cr.attendance.seed;

import cr.attendance.company.Branch;
import cr.attendance.company.BranchRepository;
import cr.attendance.company.Company;
import cr.attendance.employee.Employee;
import cr.attendance.employee.EmployeeRepository;
import cr.attendance.employee.WorkSchedule;
import cr.attendance.employee.WorkScheduleRepository;
import cr.attendance.storage.StorageService;
import cr.attendance.timeentry.Evidence;
import cr.attendance.timeentry.EvidenceRepository;
import cr.attendance.timeentry.EvidenceType;
import cr.attendance.timeentry.GeoDistance;
import cr.attendance.timeentry.LatenessCalculator;
import cr.attendance.timeentry.ReviewReason;
import cr.attendance.timeentry.TimeEntry;
import cr.attendance.timeentry.TimeEntryRepository;
import cr.attendance.timeentry.TimeEntrySource;
import cr.attendance.timeentry.TimeEntryStatus;
import cr.attendance.timeentry.TimeEntryType;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Opt-in demo day (DEV_SEED_DEMO_DAY=true): today's entries that reproduce page 2 of the blueprint (9 present
 * of 12, 2 late, 3 not clocked, 1 to review). Dev profile only. Does nothing when the company already has
 * entries for the company-local today, so a second start adds nothing. Logs counts only.
 */
@Component
@Profile("dev")
public class DemoDayEntries {

    private static final Logger log = LoggerFactory.getLogger(DemoDayEntries.class);

    private static final int OUTSIDE_METERS = 420;
    private static final double METERS_PER_DEGREE_LAT = 111_320.0;

    /** Code, clock-in, clock-out (or null), source, outside the geofence. */
    private record Plan(String code, LocalTime in, LocalTime out, TimeEntrySource source, boolean outside) {
    }

    private static final Plan[] PLANS = {
        new Plan("1001", LocalTime.of(7, 58), null, TimeEntrySource.MOBILE, false),
        new Plan("1002", LocalTime.of(8, 17), null, TimeEntrySource.MOBILE, false),
        new Plan("1003", LocalTime.of(7, 52), null, TimeEntrySource.TABLET, false),
        new Plan("1004", LocalTime.of(8, 1), null, TimeEntrySource.MOBILE, true),
        new Plan("1005", LocalTime.of(6, 0), LocalTime.of(14, 4), TimeEntrySource.MOBILE, false),
        new Plan("1007", LocalTime.of(7, 55), null, TimeEntrySource.MOBILE, false),
        new Plan("1008", LocalTime.of(8, 3), null, TimeEntrySource.MOBILE, false),
        new Plan("1009", LocalTime.of(8, 12), null, TimeEntrySource.MOBILE, false),
        new Plan("1010", LocalTime.of(7, 59), null, TimeEntrySource.MOBILE, false),
    };

    private final BranchRepository branchRepository;
    private final EmployeeRepository employeeRepository;
    private final WorkScheduleRepository workScheduleRepository;
    private final TimeEntryRepository timeEntryRepository;
    private final EvidenceRepository evidenceRepository;
    private final StorageService storage;
    private final Clock clock;

    public DemoDayEntries(BranchRepository branchRepository, EmployeeRepository employeeRepository,
            WorkScheduleRepository workScheduleRepository, TimeEntryRepository timeEntryRepository,
            EvidenceRepository evidenceRepository, StorageService storage, Clock clock) {
        this.branchRepository = branchRepository;
        this.employeeRepository = employeeRepository;
        this.workScheduleRepository = workScheduleRepository;
        this.timeEntryRepository = timeEntryRepository;
        this.evidenceRepository = evidenceRepository;
        this.storage = storage;
        this.clock = clock;
    }

    public void ensureToday(Company company) {
        Long companyId = company.getId();
        ZoneId zone = ZoneId.of(company.getTimeZone());
        LocalDate today = Instant.now(clock).atZone(zone).toLocalDate();
        if (!timeEntryRepository.findAllByCompanyIdAndWorkDate(companyId, today).isEmpty()) {
            log.info("Dev seed demo day: today already has entries, nothing added");
            return;
        }
        Branch branch = branchRepository.findAllByCompanyIdOrderByName(companyId).stream().findFirst().orElse(null);
        if (branch == null) {
            return;
        }
        int entries = 0;
        for (Plan plan : PLANS) {
            Employee employee = employeeRepository.findByCompanyIdAndCode(companyId, plan.code()).orElse(null);
            if (employee == null) {
                continue;
            }
            entries += insert(company, branch, employee, plan, TimeEntryType.CLOCK_IN, plan.in(), today, zone);
            if (plan.out() != null) {
                entries += insert(company, branch, employee, plan, TimeEntryType.CLOCK_OUT, plan.out(), today, zone);
            }
        }
        log.info("Dev seed demo day: {} entries added for today", entries);
    }

    private int insert(Company company, Branch branch, Employee employee, Plan plan, TimeEntryType type,
            LocalTime local, LocalDate today, ZoneId zone) {
        Long companyId = company.getId();
        // server_time equals effective_time; a time later than "now" is fine in the dev seed.
        Instant at = today.atTime(local).atZone(zone).toInstant();
        boolean mobile = plan.source() == TimeEntrySource.MOBILE;
        boolean outside = plan.outside() && type == TimeEntryType.CLOCK_IN;

        Integer late = null;
        if (type == TimeEntryType.CLOCK_IN) {
            WorkSchedule schedule = workScheduleRepository.findByCompanyIdAndEmployeeIdAndDayOfWeek(companyId,
                    employee.getId(), today.getDayOfWeek().getValue()).orElse(null);
            late = LatenessCalculator.lateMinutes(at, today, zone, schedule);
        }
        TimeEntry entry = new TimeEntry(companyId, employee.getId(), branch.getId(), type, at, plan.source(),
                UUID.randomUUID(), today);
        if (mobile) {
            double lat = branch.getLatitude() + (outside ? OUTSIDE_METERS / METERS_PER_DEGREE_LAT : 0);
            double lng = branch.getLongitude();
            int distance = (int) Math.round(GeoDistance.meters(lat, lng, branch.getLatitude(), branch.getLongitude()));
            Set<ReviewReason> reasons = outside ? EnumSet.of(ReviewReason.OUTSIDE_GEOFENCE)
                    : EnumSet.noneOf(ReviewReason.class);
            entry.withEvidence(at, lat, lng, distance, 10, 0, late, false,
                    outside ? TimeEntryStatus.PENDING_REVIEW : TimeEntryStatus.VALID, reasons);
        } else {
            // Tablet entry: no phone evidence.
            entry.withEvidence(null, null, null, null, null, null, late, false, TimeEntryStatus.VALID,
                    EnumSet.noneOf(ReviewReason.class));
        }
        TimeEntry saved = timeEntryRepository.save(entry);
        if (mobile) {
            String key = storage.store(companyId, YearMonth.from(at.atZone(zone)), placeholderJpeg(employee.getName()));
            evidenceRepository.save(new Evidence(companyId, saved.getId(), EvidenceType.PHOTO, key, at));
            evidenceRepository.save(new Evidence(companyId, saved.getId(), EvidenceType.GPS, null, at));
        }
        return 1;
    }

    /** A solid color with the initials of the employee. Not a real selfie. */
    static byte[] placeholderJpeg(String name) {
        int width = 360;
        int height = 480;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(Math.floorMod(name.hashCode(), 96) + 40, 95 + Math.floorMod(name.hashCode() >> 8, 60),
                    110 + Math.floorMod(name.hashCode() >> 16, 60)));
            g.fillRect(0, 0, width, height);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 120));
            String initials = initials(name);
            FontMetrics metrics = g.getFontMetrics();
            g.drawString(initials, (width - metrics.stringWidth(initials)) / 2,
                    (height - metrics.getHeight()) / 2 + metrics.getAscent());
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "jpeg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            if (!part.isEmpty() && sb.length() < 2) {
                sb.append(Character.toUpperCase(part.charAt(0)));
            }
        }
        return sb.toString();
    }
}
