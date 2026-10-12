package dev.wacamera.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.wacamera.core.ShotPoint;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandRuntimeException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import java.util.Locale;
import java.util.UUID;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Client commands edit local shots. Teleport requests go through the server's ordinary permission checks. */
public final class CameraCommands {
    private CameraCommands() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var pointIndex = argument("number", IntegerArgumentType.integer(1, 64))
                .executes(context -> capture(context, IntegerArgumentType.getInteger(context, "number"), false))
                .then(argument("x", DoubleArgumentType.doubleArg(-30_000_000, 30_000_000))
                .then(argument("y", DoubleArgumentType.doubleArg(-30_000_000, 30_000_000))
                .then(argument("z", DoubleArgumentType.doubleArg(-30_000_000, 30_000_000))
                .then(argument("yaw", FloatArgumentType.floatArg())
                .then(argument("pitch", DoubleArgumentType.doubleArg(-90, 90))
                .executes(context -> capture(context, IntegerArgumentType.getInteger(context, "number"), true)))))));
        dispatcher.register(literal("wac")
                .executes(CameraCommands::help)
                .then(literal("help").executes(CameraCommands::help))
                .then(literal("cam").executes(context -> capture(context, null, false)).then(pointIndex))
                .then(literal("goto").then(argument("number", IntegerArgumentType.integer(1, 64)).executes(context -> {
                    int index = IntegerArgumentType.getInteger(context, "number");
                    try { gotoPoint(index); }
                    catch (IllegalArgumentException | IllegalStateException e) { throw commandFailure(e); }
                    context.getSource().sendSuccess(() -> Component.translatable("wacamera.command.goto_requested", index), false);
                    return 1;
                }))));
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.translatable("wacamera.command.help"), false);
        return 1;
    }

    private static int capture(CommandContext<CommandSourceStack> context, Integer index, boolean numeric) {
        try {
            ShotPoint point = numeric ? new ShotPoint(UUID.randomUUID().toString(),
                    DoubleArgumentType.getDouble(context, "x"), DoubleArgumentType.getDouble(context, "y"),
                    DoubleArgumentType.getDouble(context, "z"), FloatArgumentType.getFloat(context, "yaw"),
                    (float) DoubleArgumentType.getDouble(context, "pitch")) : null;
            int saved = executeCam(index, point);
            context.getSource().sendSuccess(() -> Component.translatable("wacamera.command.point_saved", saved), false);
            return saved;
        } catch (IllegalArgumentException | IllegalStateException e) { throw commandFailure(e); }
    }

    public static int executeCam(Integer oneBasedIndex, ShotPoint numericOrNull) {
        return WaCameraClient.registerCommandPoint(oneBasedIndex, numericOrNull == null ? WaCameraClient.pointHere() : numericOrNull);
    }

    public static void gotoPoint(int oneBasedIndex) {
        ShotPoint point = WaCameraClient.commandPoint(oneBasedIndex);
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) throw new IllegalStateException(Component.translatable("wacamera.command.error.no_player").getString());
        if (!mc.player.hasPermissions(2)) throw new IllegalStateException(Component.translatable("wacamera.command.error.permission").getString());
        if (mc.player.getPose() != net.minecraft.world.entity.Pose.STANDING || mc.player.isPassenger())
            throw new IllegalStateException(Component.translatable("wacamera.command.error.posture").getString());
        // Stored Y is the lens position. Vanilla /tp expects feet. Never move or rotate the client entity optimistically.
        double feetY = point.y() - mc.player.getEyeHeight();
        String command = String.format(Locale.ROOT, "tp @s %.10f %.10f %.10f %.6f %.6f",
                point.x(), feetY, point.z(), point.yawDegrees(), point.pitchDegrees());
        mc.getConnection().sendCommand(command);
    }

    private static CommandRuntimeException commandFailure(RuntimeException failure) {
        return new CommandRuntimeException(Component.literal(failure.getMessage() == null ? failure.toString() : failure.getMessage()));
    }
}
