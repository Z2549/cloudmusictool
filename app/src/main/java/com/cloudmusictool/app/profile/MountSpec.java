package com.cloudmusictool.app.profile;

/**
 * 一个可定位的挂载点描述。
 *
 * 只描述“坐标”，不含 hook 逻辑；由 MountResolver 解析成 Class/Method。
 * 参数与返回类型使用 JVM 描述符（如 Z、V、Ljava/lang/String;、[I）。
 */
public final class MountSpec {

    public final String id;
    public final String featureId;
    public final String logicalName;
    public final String[] classCandidates;
    public final String methodName;
    public final String returnType;
    public final String[] paramTypes;
    public final String[] anchors;
    public final boolean critical;

    private MountSpec(String featureId, String logicalName, String[] classCandidates,
                      String methodName, String returnType, String[] paramTypes,
                      String[] anchors, boolean critical) {
        this.featureId = featureId;
        this.logicalName = logicalName;
        this.id = featureId + "/" + logicalName;
        this.classCandidates = classCandidates;
        this.methodName = methodName;
        this.returnType = returnType;
        this.paramTypes = paramTypes;
        this.anchors = anchors;
        this.critical = critical;
    }

    /** 只定位类，不定位方法。 */
    public static MountSpec clazz(String featureId, String logicalName, boolean critical,
                                  String... classCandidates) {
        return new MountSpec(featureId, logicalName, classCandidates,
                null, null, null, null, critical);
    }

    /** 定位类 + 方法（参数/返回类型可空；为空时由 resolver 按名字/返回类型消歧）。 */
    public static MountSpec method(String featureId, String logicalName, boolean critical,
                                   String className, String methodName,
                                   String returnType, String[] paramTypes, String... anchors) {
        return new MountSpec(featureId, logicalName, new String[]{className},
                methodName, returnType, paramTypes, anchors, critical);
    }

    /** 多候选类名（混淆类改名时按顺序尝试）。 */
    public static MountSpec method(String featureId, String logicalName, boolean critical,
                                   String[] classCandidates, String methodName,
                                   String returnType, String[] paramTypes, String... anchors) {
        return new MountSpec(featureId, logicalName, classCandidates,
                methodName, returnType, paramTypes, anchors, critical);
    }

    public String primaryClass() {
        return classCandidates == null || classCandidates.length == 0 ? null : classCandidates[0];
    }
}
