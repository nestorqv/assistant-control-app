package cr.attendance.auth;

import cr.attendance.common.EmployeePrincipal;
import cr.attendance.employee.Employee;
import cr.attendance.employee.EmployeeRepository;
import cr.attendance.company.Company;
import cr.attendance.company.CompanyRepository;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/**
 * Re-checks the database on every request: the employee must exist in the token's company, both must
 * be active, and the token version must match. The role comes from the database, not from the claim.
 */
@Component
public class EmployeeJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String INVALID_TOKEN = "Invalid token";

    private final EmployeeRepository employeeRepository;
    private final CompanyRepository companyRepository;

    public EmployeeJwtAuthenticationConverter(EmployeeRepository employeeRepository,
            CompanyRepository companyRepository) {
        this.employeeRepository = employeeRepository;
        this.companyRepository = companyRepository;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Long employeeId = aLong(jwt.getSubject());
        Long companyId = aLong(jwt.getClaim("companyId"));
        Long ver = aLong(jwt.getClaim("ver"));

        Employee employee = employeeRepository.findByIdAndCompanyId(employeeId, companyId)
                .orElseThrow(() -> new InvalidBearerTokenException(INVALID_TOKEN));
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new InvalidBearerTokenException(INVALID_TOKEN));
        if (!employee.isActive() || !company.isActive() || employee.getTokenVersion().longValue() != ver) {
            throw new InvalidBearerTokenException(INVALID_TOKEN);
        }

        EmployeePrincipal principal = new EmployeePrincipal(employee.getId(), employee.getCompanyId(),
                employee.getRole());
        return new EmployeeAuthentication(principal, jwt,
                List.of(new SimpleGrantedAuthority("ROLE_" + employee.getRole().name())));
    }

    private static Long aLong(Object value) {
        try {
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value instanceof String text) {
                return Long.valueOf(text);
            }
        } catch (NumberFormatException e) {
            // falls through to the generic rejection below
        }
        throw new InvalidBearerTokenException(INVALID_TOKEN);
    }
}
