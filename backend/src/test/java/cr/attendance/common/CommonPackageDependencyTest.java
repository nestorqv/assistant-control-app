package cr.attendance.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** common is the bottom layer: it must not import any feature package (no cycles). */
class CommonPackageDependencyTest {

    @Test
    void commonImportsNothingFromFeaturePackages() throws IOException {
        Path dir = Path.of("src/main/java/cr/attendance/common");
        assertThat(dir).isDirectory();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("import cr.attendance.") && !trimmed.startsWith("import cr.attendance.common.")) {
                        offenders.add(file.getFileName() + ": " + trimmed);
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }
}
