package com.victorpena.just_in_transit;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
public class JourneyCheckpoint {
    @Id public String id;
    @Lob @Column(nullable=false) public String payload;
    @Column(nullable=false) public Instant expiresAt;
    public JourneyCheckpoint() { }
}
