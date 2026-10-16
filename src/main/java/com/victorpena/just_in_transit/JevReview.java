package com.victorpena.just_in_transit;
import jakarta.persistence.*;import java.time.Instant;
@Entity public class JevReview {
    @Id public String id;
    @Column(length=64,nullable=false) public String owner;
    public Instant created,expiresAt;
    @Column(length=100) public String model;
    @Lob public String judgments;
    @Lob public String evidence;
    @Column(length=30) public String feedback;
    public JevReview() { }
}
