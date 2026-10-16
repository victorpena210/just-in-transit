package com.victorpena.just_in_transit;
import jakarta.persistence.*;
@Entity
public class BrowserCommute {
    @Id @Column(length=80) public String id;
    @Column(length=64,nullable=false) public String owner;
    @Lob @Column(nullable=false) public String payload;
    public BrowserCommute() { }
}
