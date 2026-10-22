package cr.attendance.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.LocaleResolver;

class LocaleConfigTest {

    private final LocaleResolver resolver = new LocaleConfig().localeResolver();

    static Stream<Arguments> headers() {
        return Stream.of(
                Arguments.of("en", "en"),
                Arguments.of("en-US,en;q=0.9", "en"),
                Arguments.of("es", "es"),
                Arguments.of("es-CR", "es"),
                Arguments.of(null, "es"),
                Arguments.of("fr", "es"),
                Arguments.of("fr, en;q=0.5", "en"),
                Arguments.of("*", "es"),
                Arguments.of("@@@", "es"),
                Arguments.of("en;q=0.2, es;q=0.8", "es"));
    }

    @ParameterizedTest
    @MethodSource("headers")
    void resolvesTheSupportedLanguageOrFallsBackToSpanish(String header, String expected) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (header != null) {
            request.addHeader("Accept-Language", header);
        }
        Locale locale = resolver.resolveLocale(request);
        assertThat(locale.getLanguage()).isEqualTo(expected);
        assertThat(LocaleConfig.SUPPORTED).extracting(Locale::getLanguage).contains(locale.getLanguage());
    }

    @ParameterizedTest
    @MethodSource("headers")
    void neverFailsOnVeryLongHeaders(String header, String expected) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "xx,".repeat(500) + (header == null ? "" : header));
        assertThat(resolver.resolveLocale(request).getLanguage()).isIn("es", "en");
    }
}
