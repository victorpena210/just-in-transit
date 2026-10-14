package com.victorpena.just_in_transit;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:jit-startup-test;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "via.enabled=false",
    "maps.enabled=false"
})
class JustInTransitApplicationTests {

    @Autowired
    private DataSource dataSource;

    @Test
    void contextLoads() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertTrue(
                connection.getMetaData().getURL().startsWith("jdbc:h2:mem:"),
                "The startup test must use an in-memory database"
            );
        }
    }
}
