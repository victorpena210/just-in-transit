package com.victorpena.just_in_transit;
import java.time.Clock;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
@ControllerAdvice(assignableTypes={HomeController.class,LiveMapController.class,OfflineCardController.class,JevController.class,NotificationController.class,JourneySyncController.class})
public class JourneyRestore {
    private final JourneyRecovery recovery;private final JourneySession session;private final RiderTracking tracking;private final Clock clock;
    public JourneyRestore(JourneyRecovery recovery,JourneySession session,RiderTracking tracking,Clock clock) { this.recovery=recovery;this.session=session;this.tracking=tracking;this.clock=clock; }
    @ModelAttribute public void restore(HttpServletRequest request) {
        session.restore(tracking.observe(recovery.load(request,clock.instant()),clock.instant()));
    }
}
