package cr.attendance.company;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface BranchRepository extends Repository<Branch, Long> {

    Branch save(Branch branch);

    Optional<Branch> findByIdAndCompanyId(Long id, Long companyId);

    List<Branch> findAllByCompanyIdOrderByName(Long companyId);

    // Used only by the dev seed to move the demo branch to a real location.
    @Transactional
    @Modifying
    @Query("update Branch b set b.latitude = :latitude, b.longitude = :longitude, b.radiusMeters = :radiusMeters "
            + "where b.id = :id and b.companyId = :companyId")
    int updateLocation(@Param("id") Long id, @Param("companyId") Long companyId,
            @Param("latitude") Double latitude, @Param("longitude") Double longitude,
            @Param("radiusMeters") Integer radiusMeters);
}
