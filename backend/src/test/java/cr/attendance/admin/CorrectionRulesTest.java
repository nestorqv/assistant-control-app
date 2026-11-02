package cr.attendance.admin;

import static cr.attendance.admin.AdminRulesFixtures.CR;
import static cr.attendance.admin.AdminRulesFixtures.DAY;
import static cr.attendance.admin.AdminRulesFixtures.at;
import static org.assertj.core.api.Assertions.assertThat;

import cr.attendance.timeentry.TimeEntryType;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class CorrectionRulesTest {

    private static final Duration MAX = Duration.ofHours(16);
    private static final Instant NOW = at("20:00").plus(Duration.ofDays(1));

    private static boolean in(Instant candidate, Instant now, Instant pairOut) {
        return CorrectionRules.isValid(TimeEntryType.CLOCK_IN, DAY, candidate, now, CR, pairOut, MAX);
    }

    private static boolean out(Instant candidate, Instant now, Instant pairIn) {
        return CorrectionRules.isValid(TimeEntryType.CLOCK_OUT, DAY, candidate, now, CR, pairIn, MAX);
    }

    @Test
    void futureBoundaryIsNowPlusOneMinute() {
        Instant now = at("12:00");
        assertThat(in(now.plus(Duration.ofMinutes(1)), now, null)).isTrue();
        assertThat(in(now.plus(Duration.ofMinutes(1)).plusSeconds(1), now, null)).isFalse();
    }

    @Test
    void clockInWindowIsTwelveHoursBeforeMidnightToTheEndOfTheWorkDate() {
        Instant midnight = DAY.atStartOfDay(CR).toInstant();
        assertThat(in(midnight.minus(Duration.ofHours(12)), NOW, null)).isTrue();
        assertThat(in(midnight.minus(Duration.ofHours(12)).minusSeconds(1), NOW, null)).isFalse();
        Instant endOfDay = DAY.plusDays(1).atStartOfDay(CR).toInstant();
        assertThat(in(endOfDay, NOW.plus(Duration.ofDays(2)), null)).isTrue();
        assertThat(in(endOfDay.plusSeconds(1), NOW.plus(Duration.ofDays(2)), null)).isFalse();
    }

    @Test
    void clockInMustStayBeforeAnExistingClockOut() {
        assertThat(in(at("08:00"), NOW, at("17:00"))).isTrue();
        assertThat(in(at("17:00"), NOW, at("17:00"))).isFalse();
        assertThat(in(at("18:00"), NOW, at("17:00"))).isFalse();
    }

    @Test
    void clockOutMustBeStrictlyAfterTheClockInAndWithinTheMaximumShift() {
        assertThat(out(at("08:00"), NOW, at("08:00"))).isFalse();
        assertThat(out(at("08:00").plusSeconds(1), NOW, at("08:00"))).isTrue();
        assertThat(out(at("08:00").plus(MAX), NOW, at("08:00"))).isTrue();
        assertThat(out(at("08:00").plus(MAX).plusSeconds(1), NOW, at("08:00"))).isFalse();
        assertThat(out(at("17:00"), NOW, null)).isFalse();
    }

    @Test
    void localTimeIsConvertedWithTheCompanyZone() {
        // 07:00 in Costa Rica (UTC-6, no daylight saving) is 13:00 UTC.
        assertThat(ReviewWriter.toInstant("2026-10-21T07:00", CR)).isEqualTo(Instant.parse("2026-10-21T13:00:00Z"));
        assertThat(ReviewWriter.format(Instant.parse("2026-10-21T13:00:00Z"), CR)).isEqualTo("2026-10-21T07:00-06:00");
    }

    @Test
    void daylightSavingGapAndOverlapFollowTheJavaRules() {
        ZoneId ny = ZoneId.of("America/New_York");
        // Gap on 2026-03-08: 02:30 does not exist and moves forward to 03:30 EDT.
        assertThat(ReviewWriter.toInstant("2026-03-08T02:30", ny)).isEqualTo(Instant.parse("2026-03-08T07:30:00Z"));
        // Overlap on 2026-11-01: 01:30 happens twice; the earlier offset (EDT, UTC-4) is used.
        assertThat(ReviewWriter.toInstant("2026-11-01T01:30", ny)).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
        assertThat(LocalDateTime.parse("2026-11-01T01:30").atZone(ny).getOffset().getTotalSeconds()).isEqualTo(-4 * 3600);
    }
}
