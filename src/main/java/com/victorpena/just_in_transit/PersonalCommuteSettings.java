package com.victorpena.just_in_transit;

import jakarta.persistence.*;
import java.time.*;

/** Separate from old saved commutes, so the simplified defaults cannot inherit old route settings. */
@Entity
@Table(name="personal_commute_preferences")
public class PersonalCommuteSettings {
    @Id @Column(length=64) public String id;
    public LocalDate travelDate;
    public LocalTime arriveBy;
    public int finalWalk;
    public PersonalCommuteSettings() { }
}
