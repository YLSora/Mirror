package com.mirror.common.enderman;

import com.mirror.config.MirrorConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/** Freezes and angers an Enderman when a player sees it through a mirror. */
public final class MirrorEndermanObservationGoal extends Goal {
    private final EnderMan enderman;
    @Nullable
    private Player target;
    @Nullable
    private AbstractEndermanObservationController observer;

    public MirrorEndermanObservationGoal(EnderMan enderman) {
        this.enderman = enderman;
        setFlags(EnumSet.of(Flag.JUMP, Flag.MOVE, Flag.LOOK));
    }

    public static void install(EnderMan enderman) {
        if (findGoal(enderman) == null) {
            enderman.goalSelector.addGoal(1, new MirrorEndermanObservationGoal(enderman));
        }
    }

    private void prime(Player player, AbstractEndermanObservationController observer) {
        target = player;
        this.observer = observer;
        enderman.setBeingStaredAt();
        enderman.setTarget(player);
    }

    @Override
    public boolean canUse() {
        if (!MirrorConfig.COMMON.enableEndermanObservation.get()
                || observer == null || observer.isInvalid()) {
            return false;
        }
        LivingEntity currentTarget = enderman.getTarget();
        if (currentTarget instanceof Player player) {
            target = player;
            return true;
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return MirrorConfig.COMMON.enableEndermanObservation.get()
                && target != null
                && observer != null
                && !observer.isInvalid()
                && super.canContinueToUse()
                && observer.isPlayerLookingAtEnderman(enderman, target);
    }

    @Override
    public void start() {
        if (target != null) {
            enderman.setBeingStaredAt();
            enderman.getNavigation().stop();
        }
    }

    @Override
    public void tick() {
        if (target != null) {
            enderman.getLookControl().setLookAt(target.getX(), target.getEyeY(), target.getZ());
        }
    }

    @Override
    public void stop() {
        target = null;
        observer = null;
    }

    public static boolean anger(EnderMan enderman, Player player,
                                AbstractEndermanObservationController observer) {
        if (!MirrorConfig.COMMON.enableEndermanObservation.get()) return false;
        MirrorEndermanObservationGoal goal = findGoal(enderman);
        if (goal == null) return false;
        goal.prime(player, observer);
        return true;
    }

    @Nullable
    private static MirrorEndermanObservationGoal findGoal(EnderMan enderman) {
        for (var wrapped : enderman.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof MirrorEndermanObservationGoal goal) return goal;
        }
        return null;
    }
}
