package io.github.cong;

import me.lucko.spark.common.platform.PlatformInfo;
import mindustry.core.Version;

public class MindustryPlatformInfo implements PlatformInfo {

    @Override
    public Type getType() {
        return Type.SERVER;
    }

    @Override
    public String getName() {
        return "Mindustry";
    }

    @Override
    public String getVersion() {
        return "v" + Version.build;
    }

    @Override
    public String getMinecraftVersion() {
        return "Mindustry v" + Version.build;
    }
}
