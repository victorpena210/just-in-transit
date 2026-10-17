package com.victorpena.just_in_transit;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonalCommuteRepository extends JpaRepository<PersonalCommuteSettings, String> { }
