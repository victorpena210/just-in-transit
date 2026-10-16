package com.victorpena.just_in_transit;
import static org.junit.jupiter.api.Assertions.*;import java.util.*;import org.junit.jupiter.api.Test;import tools.jackson.databind.json.JsonMapper;
class JevTests {
    @Test void disabledIntegrationDoesNotCallNetwork(){var client=new JevClient("","jev-1.13.0","off",60);assertFalse(client.available());assertThrows(IllegalStateException.class,()->client.evaluate(Map.of(),0,true));}
    @Test void requestsUseIndependentBoundedChoicesAndNoMutationInstruction(){var request=JevClient.request("jev-1.13.0",Map.of("trip",Map.of("routes","24 → 93")),2,true);var questions=(Map<?,?>)request.get("questions");assertEquals(5,questions.size());assertTrue(questions.containsKey("next_action"));assertFalse(request.containsKey("messages"));}
    @Test void parsesTypedChoicesAndRejectsUnexpectedOrUnboundedOutputs(){
        var json=JsonMapper.builder().build();String response="{\"model\":\"jev-1.13.0\",\"answers\":{\"next_action\":{\"type\":\"choice\",\"choice\":\"refresh_estimates\",\"confidence\":0.9,\"probabilities\":{\"refresh_estimates\":0.9,\"check_alternatives\":0.05,\"confirm_stage\":0.03,\"no_action\":0.01,\"uncertain\":0.01}}}}";
        assertEquals("refresh_estimates",JevClient.parse(json.readTree(response),0,true).judgments().get(0).choice());
        assertThrows(IllegalArgumentException.class,()->JevClient.parse(json.readTree(response.replace("\"choice\":\"refresh_estimates\"","\"choice\":\"board_bus\"")),0,true));
        assertThrows(IllegalArgumentException.class,()->JevClient.parse(json.readTree(response.replace("\"confidence\":0.9","\"confidence\":2")),0,true));
        assertThrows(IllegalArgumentException.class,()->JevClient.parse(json.readTree(response.replace("\"refresh_estimates\":0.9","\"refresh_estimates\":0.1")),0,true));
    }
}
