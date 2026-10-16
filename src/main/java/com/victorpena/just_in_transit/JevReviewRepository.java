package com.victorpena.just_in_transit;
import java.time.Instant;import java.util.*;import org.springframework.data.jpa.repository.JpaRepository;import org.springframework.transaction.annotation.Transactional;
public interface JevReviewRepository extends JpaRepository<JevReview,String> {
    List<JevReview> findTop20ByOwnerOrderByCreatedDesc(String owner);
    Optional<JevReview> findByIdAndOwner(String id,String owner);
    @Transactional void deleteByOwner(String owner);
    @Transactional void deleteByExpiresAtBefore(Instant now);
}
