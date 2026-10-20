package com.cloudmusictool.app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VersionGateTest {

    @Test
    public void supportedVersion_exactMatchOnly() {
        assertTrue(VersionGate.isSupportedVersion("9.6.08"));
        assertFalse(VersionGate.isSupportedVersion("9.6.09"));
        assertFalse(VersionGate.isSupportedVersion(null));
    }

    @Test
    public void shouldProceed_unknownVersionIsBestEffort() {
        assertTrue(VersionGate.shouldProceed(null, false));
    }

    @Test
    public void shouldProceed_supportedVersion() {
        assertTrue(VersionGate.shouldProceed("9.6.08", false));
    }

    @Test
    public void shouldProceed_unsupportedBlockedByDefault() {
        assertFalse(VersionGate.shouldProceed("9.6.09", false));
    }

    @Test
    public void shouldProceed_unsupportedAllowedWhenOptedIn() {
        assertTrue(VersionGate.shouldProceed("9.6.09", true));
    }
}
