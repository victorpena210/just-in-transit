package com.victorpena.just_in_transit;
import jakarta.persistence.*;import java.time.Instant;
@Entity public class TripWatch {
    @Id @Column(length=64) public String owner;
    @Column(length=36,nullable=false) public String revision;
    @Lob @Column(nullable=false) public String payload;
    @Lob public String sent;
    public Instant expiresAt,nextAttempt;
    public TripWatch() { }
}
