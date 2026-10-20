package com.cloudmusictool.app;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AbTestPolicyTest {

    private static Set<String> promoted(String... keys) {
        Set<String> set = new HashSet<>();
        Collections.addAll(set, keys);
        return set;
    }

    @Test
    public void curatedKey_allowedWithoutPromotion() {
        assertTrue(AbTestPolicy.isAllowed("tabMerge", Collections.<String>emptySet()));
        assertTrue(AbTestPolicy.isAllowed("home-headframe", null));
        assertTrue(AbTestPolicy.isAllowed("aosCrossFade", Collections.<String>emptySet()));
    }

    @Test
    public void blockedKey_rejectedEvenIfPromoted() {
        assertFalse(AbTestPolicy.isAllowed("VIP_accountcard_01", promoted("VIP_accountcard_01")));
        assertFalse(AbTestPolicy.isAllowed("svip_theme", promoted("svip_theme")));
        assertFalse(AbTestPolicy.isAllowed("song_quality_hires", promoted("song_quality_hires")));
        assertFalse(AbTestPolicy.isAllowed("login_style", promoted("login_style")));
        assertFalse(AbTestPolicy.isAllowed("cookie_sync", promoted("cookie_sync")));
        assertFalse(AbTestPolicy.isAllowed("risk_control_v2", promoted("risk_control_v2")));
    }

    @Test
    public void bootPrefixedKey_rejected() {
        assertFalse(AbTestPolicy.isAllowed("boot-foo", promoted("boot-foo")));
        assertFalse(AbTestPolicy.isAllowed("boot-", promoted("boot-")));
        assertTrue(AbTestPolicy.isBlocked("boot-foo"));
    }

    @Test
    public void newlyBlockedKeys_rejectedEvenIfPromoted() {
        String[] keys = {
                "ad_disable_ad_free_play_task",
                "hy-freetscashierbox-list-android",
                "rewarded-video-ads",
                "FH-kv-sp-cookie-v7",
                "wait-task-0805-v2",
                "cold-start-26H2-aos",
                "androidhttps",
                "aosApiHttps",
                "aosCDNHttps",
                "ab-tombstone-collect-data",
                "startup_task_play_process"
        };
        for (String key : keys) {
            assertTrue("should be blocked: " + key, AbTestPolicy.isBlocked(key));
            assertFalse("should be rejected: " + key,
                    AbTestPolicy.isAllowed(key, promoted(key)));
        }
    }

    @Test
    public void promotedKey_allowed() {
        assertTrue(AbTestPolicy.isAllowed("some-new-experiment", promoted("some-new-experiment")));
    }

    @Test
    public void nonPromotedNonCuratedKey_rejected() {
        assertFalse(AbTestPolicy.isAllowed("some-new-experiment", Collections.<String>emptySet()));
        assertFalse(AbTestPolicy.isAllowed("some-new-experiment", promoted("other-key")));
    }

    @Test
    public void exactMatchOnly_noPrefixAllowance() {
        assertFalse(AbTestPolicy.isAllowed("tabMerge_v2", Collections.<String>emptySet()));
        assertFalse(AbTestPolicy.isAllowed("home-headframe-new", Collections.<String>emptySet()));
        assertFalse(AbTestPolicy.isAllowed("tab", Collections.<String>emptySet()));
    }

    @Test
    public void invalidGroup_rejected() {
        assertFalse(AbTestPolicy.isValidGroup(null));
        assertFalse(AbTestPolicy.isValidGroup(""));
        assertFalse(AbTestPolicy.isValidGroup("t5"));
        assertFalse(AbTestPolicy.isValidGroup("C"));
        assertFalse(AbTestPolicy.isValidGroup(" t1"));
        assertTrue(AbTestPolicy.isValidGroup("c"));
        assertTrue(AbTestPolicy.isValidGroup("t"));
        assertTrue(AbTestPolicy.isValidGroup("t1"));
        assertTrue(AbTestPolicy.isValidGroup("t2"));
        assertTrue(AbTestPolicy.isValidGroup("t3"));
        assertTrue(AbTestPolicy.isValidGroup("t4"));
    }

    @Test
    public void nullOrEmptyKey_blocked() {
        assertTrue(AbTestPolicy.isBlocked(null));
        assertTrue(AbTestPolicy.isBlocked(""));
        assertFalse(AbTestPolicy.isAllowed(null, promoted("x")));
        assertFalse(AbTestPolicy.isAllowed("", promoted("x")));
    }
}
