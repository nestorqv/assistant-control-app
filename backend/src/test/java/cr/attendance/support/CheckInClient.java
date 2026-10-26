package cr.attendance.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/** Helpers for the Stage 2 integration tests: actors, multipart posts and checks on rows and files. */
@Component
public class CheckInClient {

    public record Actor(TestData.Tenant tenant, TestData.TestUser user, String token) {
        public long companyId() {
            return tenant.companyId();
        }

        public long employeeId() {
            return user.id();
        }
    }

    // Company ids restart in every fresh embedded database, so files left in target/test-uploads by a previous
    // run (without "mvn clean") would look like files of the new tenants. Empty it once per JVM, before any post.
    static {
        Path dir = Path.of("target", "test-uploads");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).filter(p -> !p.equals(dir) && !p.startsWith(dir.resolve(".upload-tmp")))
                        .forEach(p -> p.toFile().delete());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Value("${app.storage.root}")
    private String storageRoot;

    /** A new tenant with one consented employee, logged in through the real endpoint. */
    public Actor actor() throws Exception {
        return actor(data.tenant(), "EMPLOYEE", true);
    }

    public Actor actor(TestData.Tenant tenant, String role, boolean consent) throws Exception {
        TestData.TestUser user = data.user(tenant, role);
        if (consent) {
            data.consent(user.id());
        }
        return new Actor(tenant, user, data.login(tenant, user));
    }

    /** Default form: a position at the test branch with a good accuracy. Tests change the map as needed. */
    public Map<String, String> form(String type, Instant deviceTime) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("type", type);
        form.put("clientUuid", UUID.randomUUID().toString());
        form.put("deviceTime", deviceTime.toString());
        form.put("lat", "9.9325");
        form.put("lng", "-84.0796");
        form.put("accuracy", "10");
        return form;
    }

    public ResultActions post(Actor actor, Map<String, String> form, byte[] photoOrNull) throws Exception {
        return mockMvc.perform(request(actor == null ? null : actor.token(), form, photoOrNull));
    }

    public ResultActions perform(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder);
    }

    /** An ADMIN who is assigned to a branch (the seeded admin has none), consented and logged in. */
    public Actor adminWithBranch(TestData.Tenant tenant) throws Exception {
        long id = data.employee(tenant.companyId(), tenant.branchId(), "ADMIN");
        String code = jdbc.queryForObject("select code from employee where id = ?", String.class, id);
        TestData.TestUser user = new TestData.TestUser(id, code, TestData.PIN);
        data.consent(id);
        return new Actor(tenant, user, data.login(tenant, user));
    }

    public MockMultipartHttpServletRequestBuilder request(String tokenOrNull, Map<String, String> form,
            byte[] photoOrNull) {
        MockMultipartHttpServletRequestBuilder builder = multipart("/api/time-entries");
        form.forEach(builder::param);
        if (photoOrNull != null) {
            // Misleading filename and content type on purpose: the server must ignore both.
            builder.file(new MockMultipartFile("photo", "../x.php", "text/html", photoOrNull));
        }
        if (tokenOrNull != null) {
            builder.header("Authorization", "Bearer " + tokenOrNull);
        }
        return builder;
    }

    public ResultActions get(Actor actor, String path) throws Exception {
        MockHttpServletRequestBuilder builder = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path);
        if (actor != null) {
            builder.header("Authorization", "Bearer " + actor.token());
        }
        return mockMvc.perform(builder);
    }

    public JsonNode json(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    public int entryCount(long employeeId) {
        return jdbc.queryForObject("select count(*) from time_entry where employee_id = ?", Integer.class,
                employeeId);
    }

    public int evidenceCount(long employeeId) {
        return jdbc.queryForObject("""
                select count(*) from evidence ev join time_entry te on te.id = ev.time_entry_id
                 where te.employee_id = ?
                """, Integer.class, employeeId);
    }

    public String photoKey(long timeEntryId) {
        return jdbc.queryForObject(
                "select storage_key from evidence where time_entry_id = ? and type = 'PHOTO'", String.class,
                timeEntryId);
    }

    public Path storedFile(String key) {
        return Path.of(storageRoot).toAbsolutePath().resolve(key);
    }

    /** Regular files stored for one company. Tests never count the shared directory as a whole. */
    public List<Path> filesOf(long companyId) {
        Path dir = Path.of(storageRoot).toAbsolutePath().resolve(String.valueOf(companyId));
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
