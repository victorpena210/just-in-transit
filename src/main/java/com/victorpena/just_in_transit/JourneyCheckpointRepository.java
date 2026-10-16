package com.victorpena.just_in_transit;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface JourneyCheckpointRepository extends JpaRepository<JourneyCheckpoint,String> {
    java.util.Optional<JourneyCheckpoint> findFirstByOwnerOrderByExpiresAtDesc(String owner);
    @Transactional void deleteByExpiresAtBefore(Instant now);
}
