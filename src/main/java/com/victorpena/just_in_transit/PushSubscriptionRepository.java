package com.victorpena.just_in_transit;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription,String> { }
