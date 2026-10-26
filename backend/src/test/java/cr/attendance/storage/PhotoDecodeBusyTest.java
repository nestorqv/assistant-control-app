package cr.attendance.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cr.attendance.common.ApiException;
import cr.attendance.common.ErrorCode;
import cr.attendance.support.TestImages;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

/** Audit M-1: the number of simultaneous decodes is bounded and the busy path is a clean 503. */
class PhotoDecodeBusyTest {

    private static PhotoProcessor processor(int slots) {
        return new PhotoProcessor(new StorageProperties("unused", DataSize.ofMegabytes(2), 2560, 6_000_000, 1280,
                0.85f, slots, Duration.ofMillis(150), null));
    }

    private static MockMultipartFile photo() {
        return new MockMultipartFile("photo", "p.jpg", "image/jpeg", TestImages.jpeg());
    }

    @Test
    void whenEveryDecodeSlotIsTakenTheRequestGets503ServerBusyQuickly() {
        PhotoProcessor processor = processor(2);
        processor.decodePermits().acquireUninterruptibly(2);
        try {
            long start = System.nanoTime();
            assertThatThrownBy(() -> processor.process(photo())).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(e.code()).isEqualTo(ErrorCode.SERVER_BUSY);
            });
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        } finally {
            processor.decodePermits().release(2);
        }
        assertThat(processor.process(photo()).jpeg()).isNotEmpty();
    }

    @Test
    void badInputIsRefusedWithoutUsingASlot() {
        PhotoProcessor processor = processor(1);
        processor.decodePermits().acquireUninterruptibly();
        try {
            // Cheap checks come first, so a bomb is "invalid", not "busy", even when the decoder is saturated.
            assertThatThrownBy(() -> processor.process(new MockMultipartFile("photo", TestImages.pngHeaderOnly(50_000, 50_000))))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PHOTO));
        } finally {
            processor.decodePermits().release();
        }
    }

    @Test
    void theSlotIsReleasedAfterEachDecodeEvenWhenItFails() {
        PhotoProcessor processor = processor(1);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> processor.process(new MockMultipartFile("photo",
                    TestImages.truncatedJpeg()))).isInstanceOf(ApiException.class);
        }
        assertThat(processor.decodePermits().availablePermits()).isEqualTo(1);
    }

    @Test
    void parallelUploadsAboveTheCapAreServedOrTurnedAwayNeverMoreThanTheCapAtOnce() throws Exception {
        PhotoProcessor processor = processor(2);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        processor.process(new MockMultipartFile("photo", TestImages.jpeg(1500, 1500)));
                        return 200;
                    } catch (ApiException e) {
                        return e.status().value();
                    }
                }));
            }
            go.countDown();
            for (Future<Integer> f : results) {
                assertThat(f.get()).isIn(200, 503);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(processor.decodePermits().availablePermits()).isEqualTo(2);
    }
}
