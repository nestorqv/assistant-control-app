package cr.attendance.auth;

import com.github.benmanes.caffeine.cache.Ticker;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.crypto.SecretKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
public class JwtConfig {

    @Bean
    PasswordEncoder passwordEncoder(SecurityProperties props) {
        return new BCryptPasswordEncoder(props.bcryptStrength());
    }

    // Constructor form works on Security 6.5 and 7.x (withSecretKey exists only since 7.0).
    @Bean
    JwtEncoder jwtEncoder(JwtProperties props, Environment environment) {
        SecretKey key = props.secretKey(isTestProfile(environment));
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties props, Environment environment) {
        SecretKey key = props.secretKey(isTestProfile(environment));
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // The default converter invents "iat" (exp - 1 s) when it is missing, which would hide the absence
        // from the validator below. Drop the invented value so "iat" is truly required.
        Converter<Map<String, Object>, Map<String, Object>> defaults = MappedJwtClaimSetConverter
                .withDefaults(Map.of());
        decoder.setClaimSetConverter(raw -> {
            Map<String, Object> converted = new HashMap<>(defaults.convert(raw));
            if (raw.get(JwtClaimNames.IAT) == null) {
                converted.remove(JwtClaimNames.IAT);
            }
            return converted;
        });
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                new JwtTimestampValidator(props.clockSkew()),
                new JwtIssuerValidator(props.issuer()),
                required("exp"),
                required("iat"),
                required("sub"),
                required("companyId"),
                required("role"),
                required("ver")));
        return decoder;
    }

    @Bean
    LoginRateLimiter loginRateLimiter(SecurityProperties props) {
        SecurityProperties.LoginRateLimit limit = props.loginRateLimit();
        return new LoginRateLimiter(limit.maxAttempts(), limit.window(), Ticker.systemTicker());
    }

    // Only the test profile may use the committed (public) test secret.
    private static boolean isTestProfile(Environment environment) {
        return environment.acceptsProfiles(Profiles.of("test"));
    }

    private static OAuth2TokenValidator<Jwt> required(String claim) {
        return new JwtClaimValidator<Object>(claim, Objects::nonNull);
    }
}
