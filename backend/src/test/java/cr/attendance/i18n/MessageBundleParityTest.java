package cr.attendance.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import cr.attendance.common.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** The three bundles must stay in sync, and be read as UTF-8 without a BOM. */
class MessageBundleParityTest {

    private static Properties load(String name) throws IOException {
        try (InputStream in = MessageBundleParityTest.class.getResourceAsStream("/" + name)) {
            assertThat(in).as(name).isNotNull();
            Properties props = new Properties();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return props;
        }
    }

    private static byte[] rawBytes(String name) throws IOException {
        try (InputStream in = MessageBundleParityTest.class.getResourceAsStream("/" + name)) {
            return in.readAllBytes();
        }
    }

    @Test
    void allThreeFilesHaveTheSameKeys() throws IOException {
        Properties base = load("messages.properties");
        assertThat(load("messages_es.properties").keySet()).isEqualTo(base.keySet());
        assertThat(load("messages_en.properties").keySet()).isEqualTo(base.keySet());
    }

    @Test
    void noValueIsBlank() throws IOException {
        for (String file : new String[] {"messages.properties", "messages_es.properties", "messages_en.properties"}) {
            load(file).forEach((k, v) -> assertThat(String.valueOf(v).trim()).as(file + " " + k).isNotEmpty());
        }
    }

    @Test
    void everyErrorCodeHasTitleAndDetailInEveryFile() throws IOException {
        for (String file : new String[] {"messages.properties", "messages_es.properties", "messages_en.properties"}) {
            Properties props = load(file);
            for (ErrorCode code : ErrorCode.values()) {
                assertThat(props.getProperty(code.messageKey("title"))).as(file + " " + code + " title").isNotBlank();
                assertThat(props.getProperty(code.messageKey("detail"))).as(file + " " + code + " detail").isNotBlank();
            }
        }
    }

    @Test
    void baseBundleIsSpanish() throws IOException {
        assertThat(load("messages.properties")).isEqualTo(load("messages_es.properties"));
    }

    @Test
    void englishDiffersFromSpanishForEveryKey() throws IOException {
        Properties es = load("messages_es.properties");
        Properties en = load("messages_en.properties");
        es.forEach((k, v) -> assertThat(en.getProperty((String) k)).as("key " + k).isNotEqualTo(v));
    }

    @Test
    void filesHaveNoBomAndSpanishAccentsDecodeCorrectly() throws IOException {
        for (String file : new String[] {"messages.properties", "messages_es.properties", "messages_en.properties"}) {
            byte[] bytes = rawBytes(file);
            boolean bom = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                    && (bytes[2] & 0xFF) == 0xBF;
            assertThat(bom).as(file + " BOM").isFalse();
        }
        assertThat(load("messages_es.properties").getProperty("error.INVALID_CREDENTIALS.title"))
                .isEqualTo("Credenciales inválidas");
    }
}
