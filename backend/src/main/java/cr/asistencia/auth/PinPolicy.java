package cr.asistencia.auth;

import java.time.DateTimeException;
import java.time.LocalDate;

/** PIN rules for setting a PIN (seeder now, admin CRUD in Stage 4). Login does not use it. */
public final class PinPolicy {

    public static final int LONGITUD = 8;

    static final String MENSAJE =
            "El PIN debe tener exactamente 8 dígitos y no puede ser una secuencia, un patrón repetido ni una fecha.";

    private static final int ANIO_MIN = 1900;
    private static final int ANIO_MAX = 2099;

    private PinPolicy() {
    }

    public static boolean esValido(String pin) {
        if (pin == null || pin.length() != LONGITUD) {
            return false;
        }
        for (int i = 0; i < LONGITUD; i++) {
            char c = pin.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return !mismoDigito(pin) && !secuencia(pin) && !bloqueRepetido(pin) && !esFecha(pin);
    }

    public static void exigir(String pin) {
        if (!esValido(pin)) {
            throw new IllegalArgumentException(MENSAJE);
        }
    }

    private static boolean mismoDigito(String pin) {
        return pin.chars().allMatch(c -> c == pin.charAt(0));
    }

    // Each digit is +1 (or -1) from the previous one, wrapping 9 -> 0 and 0 -> 9.
    private static boolean secuencia(String pin) {
        boolean asc = true;
        boolean desc = true;
        for (int i = 1; i < LONGITUD; i++) {
            int diff = Math.floorMod(pin.charAt(i) - pin.charAt(i - 1), 10);
            asc &= diff == 1;
            desc &= diff == 9;
        }
        return asc || desc;
    }

    private static boolean bloqueRepetido(String pin) {
        return pin.substring(0, 2).repeat(4).equals(pin) || pin.substring(0, 4).repeat(2).equals(pin);
    }

    private static boolean esFecha(String pin) {
        return fecha(num(pin, 4, 8), num(pin, 2, 4), num(pin, 0, 2))      // DDMMYYYY
                || fecha(num(pin, 4, 8), num(pin, 0, 2), num(pin, 2, 4))  // MMDDYYYY
                || fecha(num(pin, 0, 4), num(pin, 4, 6), num(pin, 6, 8)); // YYYYMMDD
    }

    private static int num(String pin, int from, int to) {
        return Integer.parseInt(pin.substring(from, to));
    }

    // LocalDate.of is strict: 31022000 and 29021999 are not dates.
    private static boolean fecha(int anio, int mes, int dia) {
        if (anio < ANIO_MIN || anio > ANIO_MAX) {
            return false;
        }
        try {
            LocalDate.of(anio, mes, dia);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }
}
