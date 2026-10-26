package cr.attendance.storage;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Same limits as spring.servlet.multipart.*, but uploads are spooled to the private storage temp
 * directory instead of the shared system temp directory (audit L-2). Replaces Boot's own element.
 */
@Configuration
public class MultipartTempDirConfig {

    @Bean
    MultipartConfigElement multipartConfigElement(MultipartProperties props, LocalDiskStorageService storage) {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(props.getMaxFileSize());
        factory.setMaxRequestSize(props.getMaxRequestSize());
        factory.setFileSizeThreshold(props.getFileSizeThreshold());
        factory.setLocation(storage.tempDir().toString());
        return factory.createMultipartConfig();
    }
}
