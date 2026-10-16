package com.victorpena.just_in_transit;
import jakarta.persistence.*;
import java.time.Instant;
@Entity
public class JourneyReceipt {
    @Id @Column(length=110) public String id;
    @Column(length=64) public String owner;
    public String fingerprint;
    public Instant expiresAt;
}
