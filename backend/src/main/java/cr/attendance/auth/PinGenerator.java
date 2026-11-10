package cr.attendance.auth;

import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/**
 * Generates the initial / reset PIN of an employee: PinPolicy.LENGTH random digits (leading zeros allowed),
 * redrawn until the policy accepts it. The PIN leaves the server only in the one-time reveal response; it is
 * never logged, audited or stored in plain text.
 */
@Component
public class PinGenerator {

    private final RandomGenerator random;

    public PinGenerator(RandomGenerator random) {
        this.random = random;
    }

    public String nextPin() {
        return nextPin(null);
    }

    /** Also redraws a PIN equal to the employee code (a code is often printed or known to others). */
    public String nextPin(String employeeCode) {
        while (true) {
            StringBuilder pin = new StringBuilder(PinPolicy.LENGTH);
            for (int i = 0; i < PinPolicy.LENGTH; i++) {
                pin.append((char) ('0' + random.nextInt(10)));
            }
            String candidate = pin.toString();
            if (PinPolicy.isValid(candidate) && !candidate.equals(employeeCode)) {
                return candidate;
            }
        }
    }
}
