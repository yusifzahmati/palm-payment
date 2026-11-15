package az.palm.palmservice.domain.repositories;

import az.palm.palmservice.domain.entities.PalmUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PalmUserRepository extends JpaRepository<PalmUser,Long> {

    @Query("SELECT pu FROM PalmUser pu")
    List<PalmUser> findAll();

    @Query("SELECT u FROM PalmUser u where u.finCode = :finCode")
    PalmUser findByUniqueCode(@Param("finCode") String finCode);

    Optional<PalmUser> findByFinCode(String finCode);
}
