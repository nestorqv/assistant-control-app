package cr.attendance.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** L-1: the one text rule of the admin API, and the digit-run rule of the PIN-reset note. */
class TextRulesTest {

    private static final Pattern SAFE = Pattern.compile(TextRules.SAFE_TEXT);

    static Stream<String> unsafe() {
        return Stream.of(
                "a‮b", // right-to-left override
                "a⁦b", // isolate
                "a​b", // zero-width space
                "a‍b", // zero-width joiner
                "a⁠b", // word joiner
                "a﻿b", // BOM
                "a b", // line separator
                "a b", // paragraph separator
                "a\u0000b", // NUL
                "a\tb",
                "a\nb",
                "a\uD800b"); // lone surrogate
    }

    @ParameterizedTest
    @MethodSource("unsafe")
    void controlFormatSurrogateAndSeparatorCharactersAreRejected(String text) {
        assertThat(SAFE.matcher(text).matches()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"José Ñandú Pérez", "Ana María O Neil", "Café 24/7", "Turno 😀", "plain text"})
    void accentedSpanishAndASingleEmojiPass(String text) {
        assertThat(SAFE.matcher(text).matches()).isTrue();
    }

    @Test
    void cleanNormalizesToNfcAndStrips() {
        String decomposed = "José"; // e + combining acute
        String cleaned = TextRules.clean("  " + decomposed + "  ");
        assertThat(cleaned).isEqualTo("José").hasSize(4);
        assertThat(TextRules.clean(null)).isNull();
    }

    @Test
    void cleanOptionalTurnsAnEmptyResultIntoNull() {
        assertThat(TextRules.cleanOptional("   ")).isNull();
        assertThat(TextRules.cleanOptional(null)).isNull();
        assertThat(TextRules.cleanOptional(" Cajera ")).isEqualTo("Cajera");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234", "12 34", "1-2345-6789", "8888.8888", "x 12/34 y", "١٢٣٤",
            "12 34 56 78", "12 34 56 78", "12 34 56 78",
            "12　34　56　78"})
    void runsOfFourOrMoreDigitsAreRefused(String text) {
        assertThat(TextRules.hasDigitRun(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "12 a 34", "plain text", "ticket 12", ""})
    void shortDigitGroupsAndPlainTextPass(String text) {
        assertThat(TextRules.hasDigitRun(text)).isFalse();
    }
}
