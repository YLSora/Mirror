package com.mirror.compat;

import net.minecraftforge.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Only the currently supported optional mods install their integration mixins. */
public final class OptionalMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        if (mixin.contains("VoxyIris")) {
            return FMLLoader.getLoadingModList().getModFileById("voxy") != null
                    && FMLLoader.getLoadingModList().getModFileById("oculus") != null;
        }
        String mod = mixin.contains("Embeddium") ? "embeddium"
                : mixin.contains("Grass") ? "grassiergrass" : "voxy";
        return FMLLoader.getLoadingModList().getModFileById(mod) != null;
    }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
}
