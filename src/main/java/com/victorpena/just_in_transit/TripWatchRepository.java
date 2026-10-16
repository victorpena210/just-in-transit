package com.victorpena.just_in_transit;
import java.time.Instant;import org.springframework.data.jpa.repository.*;import org.springframework.transaction.annotation.Transactional;import org.springframework.data.repository.query.Param;
public interface TripWatchRepository extends JpaRepository<TripWatch,String> {
    java.util.List<TripWatch> findTop100ByOwnerGreaterThanOrderByOwnerAsc(String owner);
    @Transactional void deleteByExpiresAtBefore(Instant now);
    boolean existsByOwnerAndRevision(String owner,String revision);
    @Modifying @Transactional @Query("update TripWatch w set w.sent=:sent,w.nextAttempt=:next where w.owner=:owner and w.revision=:revision")
    int recordAttempt(@Param("owner") String owner,@Param("revision") String revision,@Param("sent") String sent,@Param("next") Instant next);
    @Modifying @Transactional @Query("delete from TripWatch w where w.owner=:owner and w.revision=:revision")
    int deleteRevision(@Param("owner") String owner,@Param("revision") String revision);
}
