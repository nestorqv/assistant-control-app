package cr.attendance.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cr.attendance.support.AdminClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Second authorization layer (Stage 1 I-1): every admin controller is also protected by @PreAuthorize. */
@IntegrationTest
@Import(AdminMethodSecurityTest.OnlyForAdmins.class)
class AdminMethodSecurityTest {

    /** Reachable by the URL rule (any authenticated user), so only @PreAuthorize can stop an employee. */
    @RestController
    @RequestMapping("/api/test-method-security")
    @PreAuthorize("hasRole('ADMIN')")
    static class OnlyForAdmins {
        @GetMapping
        String get() {
            return "ok";
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminClient admins;

    @Autowired
    private TestData data;

    @Test
    void everyControllerUnderApiAdminDeclaresPreAuthorizeForAdmins() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> adminControllers = new ArrayList<>();
        List<String> unprotected = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("cr.attendance")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            List<String> bases = mapping == null || mapping.path().length == 0 ? List.of("") : List.of(mapping.path());
            PreAuthorize classGuard = type.getAnnotation(PreAuthorize.class);
            boolean classGuarded = classGuard != null && "hasRole('ADMIN')".equals(classGuard.value());
            boolean admin = false;
            // The class-level mapping, and every method-level mapping (a class mapped at /api may add /admin/x).
            if (bases.stream().anyMatch(p -> p.startsWith("/api/admin"))) {
                admin = true;
                if (!classGuarded) {
                    unprotected.add(type.getSimpleName());
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                RequestMapping methodMapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (methodMapping == null) {
                    continue;
                }
                List<String> paths = methodMapping.path().length == 0 ? List.of("") : List.of(methodMapping.path());
                boolean adminMethod = bases.stream().anyMatch(base -> paths.stream()
                        .anyMatch(path -> (base + path).startsWith("/api/admin")));
                if (!adminMethod) {
                    continue;
                }
                if (!admin) {
                    admin = true;
                }
                PreAuthorize methodGuard = method.getAnnotation(PreAuthorize.class);
                boolean methodGuarded = methodGuard != null && "hasRole('ADMIN')".equals(methodGuard.value());
                String name = type.getSimpleName() + "#" + method.getName();
                if (!classGuarded && !methodGuarded && !unprotected.contains(name)) {
                    unprotected.add(name);
                }
            }
            if (admin) {
                adminControllers.add(type.getSimpleName());
            }
        }
        assertThat(adminControllers).contains("AdminEmployeeController", "AdminTimeEntryPhotoController",
                "AdminDashboardController", "AdminTimeEntryController", "ReviewQueueController",
                "AdminBranchController");
        assertThat(unprotected).isEmpty();
    }

    @Test
    void aDenialFromMethodSecurityIsAPlain403AndNeverA500() throws Exception {
        TestData.Tenant tenant = data.tenant();
        String employeeToken = admins.tokenFor(tenant, data.user(tenant, "EMPLOYEE"));
        String content = mockMvc.perform(get("/api/test-method-security").header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertThat(content).isEmpty();

        AdminClient.Admin admin = admins.admin(tenant);
        mockMvc.perform(get("/api/test-method-security").header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk());
    }

    static Stream<String> adminRoutes() {
        return Stream.of(
                "GET /api/admin/employees",
                "GET /api/admin/employees/1",
                "GET /api/admin/dashboard",
                "GET /api/admin/branches",
                "GET /api/admin/review-queue",
                "GET /api/admin/review-queue/count",
                "GET /api/admin/time-entries/1",
                "GET /api/admin/time-entries/1/photo",
                "POST /api/admin/time-entries",
                "POST /api/admin/time-entries/1/approve",
                "POST /api/admin/time-entries/1/reject",
                "POST /api/admin/time-entries/1/correct");
    }

    @ParameterizedTest
    @MethodSource("adminRoutes")
    void everyAdminRouteIsForbiddenWithAnEmptyBodyForAnEmployeeToken(String routeText) throws Exception {
        String[] route = routeText.split(" ");
        TestData.Tenant tenant = data.tenant();
        String token = admins.tokenFor(tenant, data.user(tenant, "EMPLOYEE"));
        var builder = "GET".equals(route[0]) ? get(route[1]) : post(route[1]).contentType(MediaType.APPLICATION_JSON).content("{}");
        String content = mockMvc.perform(builder.header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertThat(content).isEmpty();
    }
}
