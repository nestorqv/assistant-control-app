package cr.attendance.storage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Refuses a photo root that the web server could publish as static content: photos there would be
 * downloadable by any authenticated user who knows a key, bypassing the tenant check of PhotoService.
 */
final class StorageRootGuard {

    private static final List<List<String>> FORBIDDEN_SEQUENCES = List.of(
            List.of("src", "main", "resources"),
            List.of("src", "main", "webapp"),
            List.of("meta-inf", "resources"),
            List.of("target", "classes"),
            List.of("target", "test-classes"));

    private StorageRootGuard() {
    }

    /**
     * @param root absolute, normalized
     * @param cwd working directory: Boot serves its static/public/src/main/webapp folders as document root
     * @param staticLocations spring.web.resources.static-locations; only "file:" entries are paths
     */
    static void check(Path root, Path cwd, List<String> staticLocations) {
        List<String> names = new ArrayList<>();
        root.forEach(p -> names.add(p.toString().toLowerCase(Locale.ROOT)));
        for (List<String> forbidden : FORBIDDEN_SEQUENCES) {
            if (containsSequence(names, forbidden)) {
                throw refused(root);
            }
        }
        for (String candidate : List.of("static", "public", "src/main/webapp")) {
            if (root.startsWith(cwd.resolve(candidate).toAbsolutePath().normalize())) {
                throw refused(root);
            }
        }
        for (String location : staticLocations) {
            if (location.startsWith("file:")) {
                String raw = location.substring("file:".length());
                Path served = Path.of(raw.startsWith("//") ? raw.substring(2) : raw).toAbsolutePath().normalize();
                if (root.startsWith(served)) {
                    throw refused(root);
                }
            }
        }
    }

    private static boolean containsSequence(List<String> names, List<String> sequence) {
        for (int i = 0; i + sequence.size() <= names.size(); i++) {
            if (names.subList(i, i + sequence.size()).equals(sequence)) {
                return true;
            }
        }
        return false;
    }

    private static IllegalStateException refused(Path root) {
        return new IllegalStateException("Photo storage root is inside a folder that can be served as static "
                + "content (src/main/resources, webapp, static, public, META-INF/resources, target/classes or a "
                + "configured static location): " + root);
    }
}
