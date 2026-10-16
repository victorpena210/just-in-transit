package com.victorpena.just_in_transit;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
public class JourneyCheckpoint {
    @Id public String id;
    @Version @Column(columnDefinition="bigint default 0") public long version;
    @Column(length=64) public String owner;
    @Lob @Column(nullable=false) public String payload;
    @Column(nullable=false) public Instant expiresAt;
    public JourneyCheckpoint() { }
}
