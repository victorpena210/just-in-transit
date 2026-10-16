package com.victorpena.just_in_transit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
public interface BrowserCommuteRepository extends JpaRepository<BrowserCommute,String> {
    long countByOwner(String owner);
    @Transactional void deleteByOwner(String owner);
}
