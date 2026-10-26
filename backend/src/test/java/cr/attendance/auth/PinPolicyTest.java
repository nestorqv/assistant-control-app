package cr.attendance.auth;

import cr.attendance.common.InvalidPinException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PinPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"48207315", "07391846", "31022000"})
    void validPinsPass(String pin) {
        assertThat(PinPolicy.isValid(pin)).isTrue();
        PinPolicy.requireValid(pin);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "482073", "4820731", "482073159", "4820731a", "48 07315", "４８２０７３１５"})
    void wrongLengthOrFormatFails(String pin) {
        assertThat(PinPolicy.isValid(pin)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"00000000", "77777777"})
    void sameDigitFails(String pin) {
        assertThat(PinPolicy.isValid(pin)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678", "01234567", "23456789", "34567890", "87654321", "98765432", "10987654"})
    void sequencesFail(String pin) {
        assertThat(PinPolicy.isValid(pin)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"12121212", "90909090", "12341234", "19901990"})
    void repeatedBlocksFail(String pin) {
        assertThat(PinPolicy.isValid(pin)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"15081990", "08151990", "19900815", "29022000"})
    void datesFail(String pin) {
        assertThat(PinPolicy.isValid(pin)).isFalse();
    }

    @Test
    void impossibleDatesPass() {
        assertThat(PinPolicy.isValid("29021999")).isTrue();
    }

    @Test
    void yearBoundaries() {
        assertThat(PinPolicy.isValid("01011900")).isFalse();
        assertThat(PinPolicy.isValid("31122099")).isFalse();
        assertThat(PinPolicy.isValid("01011899")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"45678901", "56789012", "89012345", "90123456", "65432109", "09876543", "32109876"})
    void wrapAroundSequencesFailInBothDirections(String pin) {
        assertThat(PinPolicy.isValid(pin)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345679", "12345687", "13579135", "12345670", "98765430", "01234568"})
    void nearSequencesPass(String pin) {
        assertThat(PinPolicy.isValid(pin)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10101010", "01010101", "01230123", "98769876", "00110011", "55665566"})
    void moreRepeatedBlocksFail(String pin) {
        assertThat(PinPolicy.isValid(pin)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"12121213", "12123434", "12341235", "10101011"})
    void almostRepeatedBlocksPass(String pin) {
        assertThat(PinPolicy.isValid(pin)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"29022004", "02292004", "20040229", "29022000", "02292000", "20000229",
            "01012000", "31121999", "12311999", "19991231", "01012099", "20990101"})
    void validDatesInAllThreeFormatsFail(String pin) {
        assertThat(PinPolicy.isValid(pin)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"29021900", "02291900", "19000229", "29022100", "31042000", "04312000", "20000431",
            "00012000", "32012000", "01002000", "01012100", "01011899"})
    void nonDatesAndOutOfRangeYearsPass(String pin) {
        // 1900 and 2100 are not leap years; month 00 / day 00 / day 32 / April 31 do not exist.
        assertThat(PinPolicy.isValid(pin)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"48207315\n", "\n48207315", " 48207315", "48207315 ", "4820731５", "-4820731", "+4820731",
            "4820.315", "0x207315", "٤٨٢٠٧٣١٥"})
    void trailingNewlineSignsAndOtherUnicodeDigitsFail(String pin) {
        assertThat(PinPolicy.isValid(pin)).isFalse();
    }

    @Test
    void leadingZeroPinKeepsItsLength() {
        assertThat(PinPolicy.isValid("00739184")).isTrue();
        assertThat(PinPolicy.isValid("739184")).isFalse();
    }

    @Test
    void requireValidThrowsForEveryRejectedCategoryWithTheSameSafeMessage() {
        for (String pin : new String[] {null, "", "1234", "11111111", "12345678", "12121212", "12341234",
                "15081990"}) {
            assertThatThrownBy(() -> PinPolicy.requireValid(pin))
                    .isInstanceOf(InvalidPinException.class)
                    .hasMessage("PIN does not meet the policy");
        }
    }

    @Test
    void requireValidMessageDoesNotEchoTheInput() {
        assertThatThrownBy(() -> PinPolicy.requireValid("15081990"))
                .isInstanceOf(InvalidPinException.class)
                .hasMessageNotContaining("15081990");
    }
}
