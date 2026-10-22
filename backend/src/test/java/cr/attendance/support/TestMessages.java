package cr.attendance.support;

import java.nio.charset.StandardCharsets;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

/** Message source for unit tests. Must match the spring.messages.* settings in application.yml. */
public final class TestMessages {

    private TestMessages() {
    }

    public static MessageSource source() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(false);
        return source;
    }
}
