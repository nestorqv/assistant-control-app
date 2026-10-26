package cr.attendance.support;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test clock that only moves forward from the real "now": the JWT decoder validates exp against the
 * system clock, so tokens issued with a clock in the past would be rejected. To reach a given local
 * time, advance to its next occurrence.
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.MILLIS));

    public void advance(Duration d) {
        if (d.isNegative()) {
            throw new IllegalArgumentException("The test clock only moves forward");
        }
        now.updateAndGet(i -> i.plus(d));
    }

    /** Moves to the next occurrence (strictly in the future, unless already there) of this local time. */
    public Instant advanceToNext(ZoneId zone, LocalTime time) {
        Instant current = now.get();
        LocalDate date = current.atZone(zone).toLocalDate();
        Instant target = date.atTime(time).atZone(zone).toInstant();
        if (target.isBefore(current)) {
            target = date.plusDays(1).atTime(time).atZone(zone).toInstant();
        }
        Instant result = target;
        now.set(result);
        return result;
    }

    /** Like advanceToNext(zone, time), but the local date must also fall on this day of the week. */
    public Instant advanceToNext(ZoneId zone, DayOfWeek day, LocalTime time) {
        Instant result = advanceToNext(zone, time);
        while (result.atZone(zone).getDayOfWeek() != day) {
            advance(Duration.ofDays(1));
            result = now.get();
        }
        return result;
    }

    public void set(Instant instant) {
        now.updateAndGet(i -> {
            if (instant.isBefore(i)) {
                throw new IllegalArgumentException("The test clock only moves forward");
            }
            return instant;
        });
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
