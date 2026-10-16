package com.victorpena.just_in_transit;
import jakarta.persistence.*;
@Entity public class PushSubscription {
    @Id @Column(length=64) public String owner;
    @Column(length=2048,nullable=false) public String endpoint;
    @Column(length=200,nullable=false) public String p256dh;
    @Column(length=100,nullable=false) public String auth;
    public PushSubscription() { }
}
