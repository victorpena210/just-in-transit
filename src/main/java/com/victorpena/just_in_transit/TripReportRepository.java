package com.victorpena.just_in_transit;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import org.springframework.transaction.annotation.Transactional;
public interface TripReportRepository extends JpaRepository<TripReport,String> {
    Page<TripReport> findByOwner(String owner,Pageable page);
    Optional<TripReport> findByIdAndOwner(String id,String owner);
    @Transactional void deleteByIdAndOwner(String id,String owner);
    @Transactional void deleteByOwner(String owner);
    @Modifying @Transactional @Query("update TripReport r set r.owner = :owner where r.owner is null") int claimLegacy(@org.springframework.data.repository.query.Param("owner") String owner);
}
