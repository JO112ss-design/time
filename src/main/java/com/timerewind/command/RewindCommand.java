package com.timerewind.command;

import com.mojang.brigadier.CommandDispatcher;
import com.timerewind.core.RewindManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /timerewind toggle              自分の巻き戻しを ON/OFF
 * /timerewind status              状態表示
 * /timerewind trigger [player]    今すぐ巻き戻す（OP、クールダウン無視）
 */
public final class RewindCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("timerewind")
                .then(Commands.literal("toggle")
                        .executes(ctx -> toggle(ctx.getSource(), ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource(), ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("trigger")
                        .requires(s -> s.hasPermission(2))
                        .executes(ctx -> trigger(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(ctx -> trigger(ctx.getSource(), EntityArgument.getPlayer(ctx, "target"))))));
    }

    private static int toggle(CommandSourceStack src, ServerPlayer p) {
        boolean on = RewindManager.get().toggle(p);
        src.sendSuccess(() -> Component.translatable(on ? "timerewind.command.toggle.on" : "timerewind.command.toggle.off"), false);
        return on ? 1 : 0;
    }

    private static int status(CommandSourceStack src, ServerPlayer p) {
        RewindManager m = RewindManager.get();
        String seconds = String.valueOf(m.rewindTicks() / 20.0);
        String history = String.valueOf(m.historySize(p));
        String cooldown = String.valueOf(m.cooldownRemaining(p));
        String personal = m.isEnabledFor(p) ? "ON" : "OFF";
        src.sendSuccess(() -> Component.translatable("timerewind.command.status", seconds, history, cooldown, personal), false);
        return 1;
    }

    private static int trigger(CommandSourceStack src, ServerPlayer target) {
        if (!RewindManager.get().forceTrigger(target)) return 0;
        src.sendSuccess(() -> Component.translatable("timerewind.command.triggered", target.getDisplayName()), true);
        return 1;
    }

    private RewindCommand() {}
}
