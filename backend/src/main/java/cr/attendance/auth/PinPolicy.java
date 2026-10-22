package cr.attendance.auth;

import java.time.DateTimeException;
import java.time.LocalDate;

/** PIN rules for setting a PIN (seeder now, admin CRUD in Stage 4). Login does not use it. */
public final class PinPolicy {

    public static final int LENGTH = 8;

    /** Bundle key of the user-facing text; the message itself lives in messages*.properties. */
    public static final String MESSAGE_KEY = "error.INVALID_PIN.detail";

    private static final int MIN_YEAR = 1900;
    private static final int MAX_YEAR = 2099;

    private PinPolicy() {
    }

    public static boolean isValid(String pin) {
        if (pin == null || pin.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < LENGTH; i++) {
            char c = pin.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return !allSameDigit(pin) && !isSequence(pin) && !isRepeatedBlock(pin) && !isDate(pin);
    }

    /** Never includes the PIN in the exception. */
    public static void requireValid(String pin) {
        if (!isValid(pin)) {
            throw new InvalidPinException();
        }
    }

    private static boolean allSameDigit(String pin) {
        return pin.chars().allMatch(c -> c == pin.charAt(0));
    }

    // Each digit is +1 (or -1) from the previous one, wrapping 9 -> 0 and 0 -> 9.
    private static boolean isSequence(String pin) {
        boolean asc = true;
        boolean desc = true;
        for (int i = 1; i < LENGTH; i++) {
            int diff = Math.floorMod(pin.charAt(i) - pin.charAt(i - 1), 10);
            asc &= diff == 1;
            desc &= diff == 9;
        }
        return asc || desc;
    }

    private static boolean isRepeatedBlock(String pin) {
        return pin.substring(0, 2).repeat(4).equals(pin) || pin.substring(0, 4).repeat(2).equals(pin);
    }

    private static boolean isDate(String pin) {
        return isValidDate(digits(pin, 4, 8), digits(pin, 2, 4), digits(pin, 0, 2))      // DDMMYYYY
                || isValidDate(digits(pin, 4, 8), digits(pin, 0, 2), digits(pin, 2, 4))  // MMDDYYYY
                || isValidDate(digits(pin, 0, 4), digits(pin, 4, 6), digits(pin, 6, 8)); // YYYYMMDD
    }

    private static int digits(String pin, int from, int to) {
        return Integer.parseInt(pin.substring(from, to));
    }

    // LocalDate.of is strict: 31022000 and 29021999 are not dates.
    private static boolean isValidDate(int year, int month, int day) {
        if (year < MIN_YEAR || year > MAX_YEAR) {
            return false;
        }
        try {
            LocalDate.of(year, month, day);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }
}
