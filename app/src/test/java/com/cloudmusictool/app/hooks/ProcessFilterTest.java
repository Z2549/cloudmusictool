package com.cloudmusictool.app.hooks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProcessFilterTest {

    @Test
    public void shouldHook_mainProcess() {
        assertTrue(ProcessFilter.shouldHook("com.netease.cloudmusic"));
    }

    @Test
    public void shouldHook_rejectsPlayProcess() {
        assertFalse(ProcessFilter.shouldHook("com.netease.cloudmusic:play"));
    }

    @Test
    public void shouldHook_rejectsPatchAndSafemode() {
        assertFalse(ProcessFilter.shouldHook("com.netease.cloudmusic:patch"));
        assertFalse(ProcessFilter.shouldHook("com.netease.cloudmusic:safemode"));
        assertFalse(ProcessFilter.shouldHook("com.netease.cloudmusic:heap_analysis"));
        assertFalse(ProcessFilter.shouldHook("com.netease.cloudmusic:leakcanary"));
    }

    @Test
    public void shouldHook_rejectsUnknownSecondaryProcess() {
        assertFalse(ProcessFilter.shouldHook("com.netease.cloudmusic:push"));
    }

    @Test
    public void shouldHook_nullFallsBackToApplicationProcessName() {
        // 单测环境 Application.getProcessName() 返回 null，回退失败时应安全返回 false。
        assertFalse(ProcessFilter.shouldHook(null));
    }

    @Test
    public void isPlayProcess_matchesSuffix() {
        assertTrue(ProcessFilter.isPlayProcess("com.netease.cloudmusic:play"));
        assertFalse(ProcessFilter.isPlayProcess("com.netease.cloudmusic"));
        assertFalse(ProcessFilter.isPlayProcess(null));
    }
}
