package cr.attendance.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

@Configuration
public class LocaleConfig {

    public static final Locale DEFAULT = Locale.of("es");
    public static final List<Locale> SUPPORTED = List.of(Locale.of("es"), Locale.of("en"));

    // The bean name must be "localeResolver": DispatcherServlet looks it up by that name.
    @Bean
    LocaleResolver localeResolver() {
        return new SupportedLanguageResolver();
    }

    /**
     * Parses Accept-Language itself instead of using request.getLocale(): for "*" or an unparseable header
     * the servlet container answers with the JVM default locale, which would let the server's OS language
     * decide the response language. Language-only matching makes en-US resolve to en and es-CR to es.
     */
    static final class SupportedLanguageResolver extends AcceptHeaderLocaleResolver {

        SupportedLanguageResolver() {
            setSupportedLocales(SUPPORTED);
            setDefaultLocale(DEFAULT);
        }

        @Override
        public Locale resolveLocale(HttpServletRequest request) {
            String header = request.getHeader("Accept-Language");
            if (header == null || header.isBlank()) {
                return DEFAULT;
            }
            try {
                Locale match = Locale.lookup(Locale.LanguageRange.parse(header), SUPPORTED);
                return match != null ? match : DEFAULT;
            } catch (IllegalArgumentException e) {
                return DEFAULT;
            }
        }
    }
}
