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
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
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

    private static final Color[] SKIN_TONES = {
        new Color(0xF1C9A5), new Color(0xE0AC84), new Color(0xC68642), new Color(0x8D5524), new Color(0x6B4026),
    };
    private static final Color[] HAIR_COLORS = {
        new Color(0x1C1410), new Color(0x4A2E1B), new Color(0x7A4A22), new Color(0xB5803A), new Color(0x555555),
    };
    private static final Color[] SHIRT_COLORS = {
        new Color(0x2F6DB5), new Color(0xC0504D), new Color(0x3E9B6B), new Color(0x8064A2), new Color(0xE08A2E),
    };

    /** A drawn person avatar picked from the name. Not a real selfie. */
    static byte[] placeholderJpeg(String name) {
        int width = 360;
        int height = 480;
        int hash = name.hashCode();
        Color skin = SKIN_TONES[Math.floorMod(hash, SKIN_TONES.length)];
        Color hair = HAIR_COLORS[Math.floorMod(hash >> 4, HAIR_COLORS.length)];
        Color shirt = SHIRT_COLORS[Math.floorMod(hash >> 8, SHIRT_COLORS.length)];
        int hairStyle = Math.floorMod(hash >> 12, 3);
        Color background = new Color(200 + Math.floorMod(hash >> 16, 40), 215 + Math.floorMod(hash >> 20, 30),
                225 + Math.floorMod(hash >> 24, 25));
        Color dark = new Color(0x2B2B2B);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(background);
            g.fillRect(0, 0, width, height);

            // Long hair sits behind the head and shoulders.
            g.setColor(hair);
            if (hairStyle == 1) {
                g.fill(new RoundRectangle2D.Double(92, 115, 176, 260, 90, 90));
            } else if (hairStyle == 2) {
                g.fill(new Ellipse2D.Double(85, 105, 190, 190));
            }
            // Shoulders, neck, ears, face.
            g.setColor(shirt);
            g.fill(new RoundRectangle2D.Double(40, 370, 280, 220, 140, 140));
            g.setColor(skin.darker());
            g.fill(new Rectangle2D.Double(152, 300, 56, 90));
            g.setColor(skin);
            g.fill(new Ellipse2D.Double(98, 215, 24, 44));
            g.fill(new Ellipse2D.Double(238, 215, 24, 44));
            g.fill(new Ellipse2D.Double(105, 135, 150, 195));
            // V-neck over the neck.
            g.setColor(skin.darker());
            g.fill(new Polygon(new int[] {150, 210, 180}, new int[] {372, 372, 410}, 3));

            // Front hair: a dome over the top of the face.
            g.setColor(hair);
            double domeHeight = hairStyle == 0 ? 110 : 140;
            g.fill(new Arc2D.Double(100, 128, 160, domeHeight, 0, 180, Arc2D.CHORD));

            // Eyes, eyebrows, smile.
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(142, 222, 28, 18));
            g.fill(new Ellipse2D.Double(190, 222, 28, 18));
            g.setColor(dark);
            g.fill(new Ellipse2D.Double(151, 224, 12, 14));
            g.fill(new Ellipse2D.Double(199, 224, 12, 14));
            g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(hair.darker());
            g.draw(new Line2D.Double(140, 207, 170, 203));
            g.draw(new Line2D.Double(190, 203, 220, 207));
            g.setColor(dark);
            g.draw(new Arc2D.Double(150, 255, 60, 44, 200, 140, Arc2D.OPEN));
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
}
