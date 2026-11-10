package cr.attendance.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/** Counts only: no test prints a generated PIN. */
class PinGeneratorTest {

    @Test
    void tenThousandSeededPinsAreAllValidEightDigitsAndSomeStartWithZero() {
        PinGenerator generator = new PinGenerator(new Random(42));
        int leadingZero = 0;
        int invalid = 0;
        for (int i = 0; i < 10_000; i++) {
            String pin = generator.nextPin();
            if (!PinPolicy.isValid(pin) || !pin.matches("\\d{8}")) {
                invalid++;
            }
            if (pin.startsWith("0")) {
                leadingZero++;
            }
        }
        assertThat(invalid).isZero();
        assertThat(leadingZero).isPositive();
    }

    @Test
    void anInvalidDrawIsRedrawn() {
        // 12345678 is a sequence (refused by the policy); the second draw is valid.
        int[] digits = {1, 2, 3, 4, 5, 6, 7, 8, 4, 8, 2, 0, 7, 3, 1, 5};
        RandomGenerator scripted = new RandomGenerator() {
            private int next;

            @Override
            public long nextLong() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int nextInt(int bound) {
                return digits[next++];
            }
        };
        String pin = new PinGenerator(scripted).nextPin();
        assertThat(PinPolicy.isValid(pin)).isTrue();
        assertThat(pin.equals("48207315")).as("the scripted digits become the PIN").isTrue();
    }

    @Test
    void aPinEqualToTheEmployeeCodeIsSkipped() {
        // First draw is a valid PIN that equals the code; the second differs.
        int[] digits = {4, 8, 2, 0, 7, 3, 1, 5, 7, 3, 9, 2, 0, 5, 1, 8};
        RandomGenerator scripted = new RandomGenerator() {
            private int next;

            @Override
            public long nextLong() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int nextInt(int bound) {
                return digits[next++];
            }
        };
        String pin = new PinGenerator(scripted).nextPin("48207315");
        assertThat(pin.equals("48207315")).as("a PIN equal to the code is skipped").isFalse();
        assertThat(PinPolicy.isValid(pin)).isTrue();
    }
}
