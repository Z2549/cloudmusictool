package com.cloudmusictool.app.profile;

import java.util.ArrayList;
import java.util.List;

/** 9.6.08 的挂载点总表：由 AD/PV 与 UI 两个分片拼装。 */
public final class Profile9608 {

    public static final long VERSION_CODE = 9006008L;
    public static final String VERSION_NAME = "9.6.08";

    public static final TargetProfile PROFILE = build();

    private Profile9608() {
    }

    private static TargetProfile build() {
        List<MountSpec> all = new ArrayList<>();
        all.addAll(ProfileAdPv.mounts());
        all.addAll(ProfileUi.mounts());
        return new TargetProfile(VERSION_CODE, VERSION_NAME, all);
    }
}
