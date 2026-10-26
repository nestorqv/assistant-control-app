package cr.attendance.timeentry;

import java.time.Instant;
import java.time.LocalDateTime;

public record TimeResponse(Instant serverTime, long epochMillis, String timeZone, LocalDateTime localDateTime) {
}
