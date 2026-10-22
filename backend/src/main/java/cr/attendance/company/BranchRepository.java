package cr.attendance.company;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface BranchRepository extends Repository<Branch, Long> {

    Branch save(Branch branch);

    Optional<Branch> findByIdAndCompanyId(Long id, Long companyId);

    List<Branch> findAllByCompanyIdOrderByName(Long companyId);
}
