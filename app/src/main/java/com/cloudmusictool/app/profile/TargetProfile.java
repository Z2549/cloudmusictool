package com.cloudmusictool.app.profile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一个宿主版本对应的挂载点集合。 */
public final class TargetProfile {

    public final long versionCode;
    public final String versionName;
    public final List<MountSpec> mounts;

    public TargetProfile(long versionCode, String versionName, List<MountSpec> mounts) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.mounts = Collections.unmodifiableList(new ArrayList<>(mounts));
    }

    public List<MountSpec> forFeature(String featureId) {
        if (featureId == null) {
            return Collections.emptyList();
        }
        List<MountSpec> out = new ArrayList<>();
        for (MountSpec spec : mounts) {
            if (featureId.equals(spec.featureId)) {
                out.add(spec);
            }
        }
        return out;
    }

    public int criticalCount(String featureId) {
        int n = 0;
        for (MountSpec spec : mounts) {
            if (featureId.equals(spec.featureId) && spec.critical) {
                n++;
            }
        }
        return n;
    }

    public MountSpec findClassSpec(String className) {
        if (className == null) {
            return null;
        }
        for (MountSpec spec : mounts) {
            if (spec.classCandidates == null) {
                continue;
            }
            for (String candidate : spec.classCandidates) {
                if (className.equals(candidate)) {
                    return spec;
                }
            }
        }
        return null;
    }

    public MountSpec findMethodSpec(String className, String methodName) {
        if (className == null || methodName == null) {
            return null;
        }
        for (MountSpec spec : mounts) {
            if (!methodName.equals(spec.methodName) || spec.classCandidates == null) {
                continue;
            }
            for (String candidate : spec.classCandidates) {
                if (className.equals(candidate)) {
                    return spec;
                }
            }
        }
        return null;
    }
}
