package com.victorpena.just_in_transit;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PublicAccessSettingsTests {
    @Test void localAccessRemainsAvailableWithoutPassword() {
        for (String address : java.util.List.of("127.0.0.1", "::1", "[::1]")) {
            var access = new AccessSettings(false, address, "victor", "");
            assertFalse(access.enabled());
            assertFalse(access.publicAccess());
        }
    }

    @Test void unprotectedExternalBindingStillRequiresExplicitOptIn() {
        assertThrows(IllegalStateException.class,
                () -> new AccessSettings(false, "0.0.0.0", "victor", ""));
        assertThrows(IllegalStateException.class,
                () -> new AccessSettings(false, "0.0.0.0", "victor", "", false));
    }

    @Test void publicAccessNeedsNoWebsiteCredentials() {
        var access = new AccessSettings(false, "0.0.0.0", "", "", true);
        assertFalse(access.enabled());
        assertTrue(access.publicAccess());
    }

    @Test void protectedAccessStillWorksWithValidCredentials() {
        var access = new AccessSettings(true, "0.0.0.0", "victor", "test-only-long-password");
        assertTrue(access.enabled());
        assertFalse(access.publicAccess());
    }

    @Test void protectedAccessStillRejectsMissingOrShortPassword() {
        assertThrows(IllegalStateException.class,
                () -> new AccessSettings(true, "0.0.0.0", "victor", ""));
        assertThrows(IllegalStateException.class,
                () -> new AccessSettings(true, "0.0.0.0", "victor", "too-short"));
    }

    @Test void conflictingModesFailRatherThanSilentlyRemovingLogin() {
        assertThrows(IllegalStateException.class,
                () -> new AccessSettings(true, "0.0.0.0", "victor", "test-only-long-password", true));
    }
}
