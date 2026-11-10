package cr.attendance.common;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * The one text rule of the admin API (audit finding L-1): free text is NFC-normalized and stripped, then must
 * not contain control, format (bidi overrides, zero-width, BOM), surrogate or line/paragraph separator
 * characters. Request records call clean in their compact constructor, so lengths are measured afterwards.
 */
public final class TextRules {

    /** For @Pattern. Every character that is not a control, format, surrogate or separator character. */
    public static final String SAFE_TEXT = "^[^\\p{Cc}\\p{Cf}\\p{Cs}\\p{Zl}\\p{Zp}]*$";

    // A PIN (8 digits, shown 4+4), an id number, a phone: 4 or more digits, separators allowed between them.
    // UNICODE_CHARACTER_CLASS: \s also matches NBSP, U+202F, U+2007, U+3000, as in the client (JavaScript) rule.
    private static final Pattern DIGIT_RUN = Pattern.compile("\\p{Nd}(?:[\\s./-]*\\p{Nd}){3,}",
            Pattern.UNICODE_CHARACTER_CLASS);

    private TextRules() {
    }

    public static String clean(String text) {
        return text == null ? null : Normalizer.normalize(text, Normalizer.Form.NFC).strip();
    }

    /** Like clean, and an empty result becomes null. */
    public static String cleanOptional(String text) {
        String cleaned = clean(text);
        return cleaned == null || cleaned.isEmpty() ? null : cleaned;
    }

    public static boolean hasDigitRun(String text) {
        return text != null && DIGIT_RUN.matcher(text).find();
    }
}
