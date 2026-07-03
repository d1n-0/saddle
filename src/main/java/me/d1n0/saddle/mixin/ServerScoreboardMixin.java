package me.d1n0.saddle.mixin;

import me.d1n0.saddle.debugger.DebugSession;
import me.d1n0.saddle.debugger.TtdTrace;

import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.ScoreHolder;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds scoreboard writes into the time-travel delta buffer. */
@Mixin(ServerScoreboard.class)
public abstract class ServerScoreboardMixin {

    @Inject(method = "onScoreChanged", at = @At("HEAD"))
    private void saddle$scoreChanged(ScoreHolder holder, Objective objective, Score score,
            CallbackInfo ci) {
        if (DebugSession.armed()) {
            TtdTrace.scoreChanged(objective.getName(), holder.getScoreboardName(), score.value());
        }
    }

    @Inject(method = "onPlayerScoreRemoved", at = @At("HEAD"))
    private void saddle$scoreRemoved(ScoreHolder holder, Objective objective, CallbackInfo ci) {
        if (DebugSession.armed()) {
            TtdTrace.scoreRemoved(objective.getName(), holder.getScoreboardName());
        }
    }

    @Inject(method = "onPlayerRemoved", at = @At("HEAD"))
    private void saddle$playerRemoved(ScoreHolder holder, CallbackInfo ci) {
        if (DebugSession.armed()) {
            TtdTrace.allScoresRemoved(holder.getScoreboardName());
        }
    }
}
