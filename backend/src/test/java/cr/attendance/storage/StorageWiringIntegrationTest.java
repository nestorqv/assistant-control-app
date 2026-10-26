package cr.attendance.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import cr.attendance.support.CheckInClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestImages;
import jakarta.servlet.MultipartConfigElement;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartResolver;

/** Audit L-1, L-2 and M-1 wiring that MockMvc cannot exercise through a real container. */
@IntegrationTest
class StorageWiringIntegrationTest {

    @Autowired
    private MultipartProperties multipartProperties;

    @Autowired
    private MultipartConfigElement multipartConfig;

    @Autowired
    private LocalDiskStorageService storage;

    @Autowired
    private MultipartResolver multipartResolver;

    @Autowired
    private PhotoProcessor photoProcessor;

    @Autowired
    private CheckInClient client;

    @Autowired
    private ApplicationContext context;

    @Test
    void multipartIsParsedLazilySoOnlyHandlersThatBindAFormTouchTheBody() {
        assertThat(multipartProperties.isResolveLazily()).isTrue();
        assertThat(ReflectionTestUtils.getField(multipartResolver, "resolveLazily")).isEqualTo(true);
    }

    @Test
    void uploadsAreSpooledToThePrivateStorageTempDirNotTheSystemOne() {
        assertThat(multipartConfig.getLocation()).isEqualTo(storage.tempDir().toString());
        assertThat(storage.tempDir()).isDirectory().isAbsolute();
        assertThat(storage.tempDir().toString()).doesNotContain(System.getProperty("java.io.tmpdir").replace("\\", "/"));
        assertThat(multipartConfig.getMaxFileSize()).isEqualTo(3L * 1024 * 1024);
        assertThat(multipartConfig.getMaxRequestSize()).isEqualTo(4L * 1024 * 1024);
    }

    @Test
    void theTempDirHoldsNoLeftoversAfterRequests() throws Exception {
        CheckInClient.Actor actor = client.actor();
        client.post(actor, client.form("CLOCK_IN", java.time.Instant.now()), TestImages.jpeg())
                .andExpect(status().isCreated());
        try (var files = java.nio.file.Files.list(storage.tempDir())) {
            assertThat(files.filter(p -> p.getFileName().toString().startsWith("upload_"))).isEmpty();
        }
    }

    @Test
    void whenTheDecoderIsSaturatedAClockInIsA503ServerBusyAndWritesNothing() throws Exception {
        CheckInClient.Actor actor = client.actor();
        int filesBefore = client.filesOf(actor.companyId()).size();
        int slots = photoProcessor.decodePermits().availablePermits();
        photoProcessor.decodePermits().acquireUninterruptibly(slots);
        try {
            var result = client.post(actor, client.form("CLOCK_IN", java.time.Instant.now()), TestImages.jpeg())
                    .andExpect(status().isServiceUnavailable());
            JsonNode body = client.json(result);
            assertThat(body.get("code").asText()).isEqualTo("SERVER_BUSY");
            assertThat(result.andReturn().getResponse().getHeader("Retry-After")).isEqualTo("5");
            assertThat(client.entryCount(actor.employeeId())).isZero();
            assertThat(client.filesOf(actor.companyId())).hasSize(filesBefore);
        } finally {
            photoProcessor.decodePermits().release(slots);
        }
        client.post(actor, client.form("CLOCK_IN", java.time.Instant.now()), TestImages.jpeg())
                .andExpect(status().isCreated());
        assertThat(context.getBeanNamesForType(LocalDiskStorageService.class)).hasSize(1);
        assertThat(TimeUnit.SECONDS.toMillis(2)).isPositive();
    }
}
