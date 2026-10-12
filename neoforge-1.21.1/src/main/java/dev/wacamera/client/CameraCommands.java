package dev.wacamera.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.wacamera.core.ShotPoint;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import java.util.Locale;
import java.util.UUID;

/** Local point editing; teleport requests always go through the server's vanilla command handling. */
public final class CameraCommands {
    private CameraCommands() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("wac")
                .executes(context -> help())
                .then(Commands.literal("help").executes(context -> help()))
                .then(Commands.literal("cam")
                        .executes(context -> capture(null, null))
                        .then(Commands.argument("number", IntegerArgumentType.integer(1, 64))
                                .executes(context -> capture(IntegerArgumentType.getInteger(context, "number"), null))
                                .then(Commands.argument("x", DoubleArgumentType.doubleArg(-30_000_000, 30_000_000))
                                        .then(Commands.argument("y", DoubleArgumentType.doubleArg(-30_000_000, 30_000_000))
                                                .then(Commands.argument("z", DoubleArgumentType.doubleArg(-30_000_000, 30_000_000))
                                                        .then(Commands.argument("yaw", FloatArgumentType.floatArg())
                                                                .then(Commands.argument("pitch", DoubleArgumentType.doubleArg(-90, 90))
                                                                        .executes(CameraCommands::captureNumeric))))))))
                .then(Commands.literal("goto")
                        .then(Commands.argument("number", IntegerArgumentType.integer(1, 64))
                                .executes(context -> {
                                    try { gotoPoint(IntegerArgumentType.getInteger(context, "number")); return 1; }
                                    catch (IllegalArgumentException | IllegalStateException failure) { throw commandFailure(failure); }
                                }))));
    }

    private static int help() {
        message(Component.translatable("wacamera.command.help"));
        return 1;
    }

    private static int captureNumeric(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        try {
            ShotPoint point = new ShotPoint(UUID.randomUUID().toString(),
                    DoubleArgumentType.getDouble(context, "x"), DoubleArgumentType.getDouble(context, "y"),
                    DoubleArgumentType.getDouble(context, "z"), FloatArgumentType.getFloat(context, "yaw"),
                    (float) DoubleArgumentType.getDouble(context, "pitch"));
            return capture(IntegerArgumentType.getInteger(context, "number"), point);
        } catch (IllegalArgumentException failure) { throw commandFailure(failure); }
    }

    private static int capture(Integer index, ShotPoint point) throws CommandSyntaxException {
        try { return executeCam(index, point); }
        catch (IllegalArgumentException | IllegalStateException failure) { throw commandFailure(failure); }
    }

    /** Public for the client smoke test; the command tree calls exactly this path. */
    public static int executeCam(Integer oneBasedIndex, ShotPoint numericOrNull) {
        int index = WaCameraClient.registerCommandPoint(oneBasedIndex,
                numericOrNull == null ? WaCameraClient.pointHere() : numericOrNull);
        message(Component.translatable("wacamera.command.point_saved", index));
        return index;
    }

    public static void gotoPoint(int oneBasedIndex) {
        ShotPoint point = WaCameraClient.commandPoint(oneBasedIndex);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player.getPermissionLevel() < 2)
            throw new IllegalStateException(Component.translatable("wacamera.command.error.permission").getString());
        if (mc.player.getPose() != net.minecraft.world.entity.Pose.STANDING || mc.player.isPassenger())
            throw new IllegalStateException(Component.translatable("wacamera.command.error.posture").getString());
        double feetY = point.y() - mc.player.getEyeHeight();
        // Plain decimal formatting also accepts tiny coordinates, unlike scientific notation in vanilla commands.
        String command = String.format(Locale.ROOT, "tp @s %.10f %.10f %.10f %.6f %.6f",
                point.x(), feetY, point.z(), point.yawDegrees(), point.pitchDegrees());
        mc.player.connection.sendCommand(command);
        message(Component.translatable("wacamera.command.goto_requested", oneBasedIndex));
    }

    private static void message(Component text) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(text, false);
    }

    private static CommandSyntaxException commandFailure(RuntimeException failure) {
        return new SimpleCommandExceptionType(Component.literal(failure.getMessage() == null ? failure.toString() : failure.getMessage())).create();
    }
}
