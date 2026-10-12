package dev.wacamera.client;

import dev.wacamera.core.ShotDefinition;
import dev.wacamera.core.ShotPoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraftforge.client.ClientCommandHandler;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;

/** Uses the real registered client dispatcher and the isolated smoke world's authoritative server. */
final class CommandSmokeTestRunner {
    private static int phase;
    private static long deadline;
    private static ShotPoint destination;
    private static CompletableFuture<Boolean> serverCheck;

    static boolean tick() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (phase == 3) return true;
        if (phase == 0) {
            WaCameraClient.setEdited(null);
            // Merely inspecting/closing the GUI must not consume automatic point number 1.
            mc.setScreen(new ShotScreen());
            mc.screen.onClose();
            ShotPoint view = WaCameraClient.pointHere();
            check(execute("wac cam") == 1 && WaCameraClient.edited().points().size() == 1, "First automatic point must be 1");
            ShotPoint first = WaCameraClient.edited().points().get(0);
            check(sameView(first, view), "Automatic point did not capture XYZ/yaw/pitch");
            check(execute("wac cam") == 2 && WaCameraClient.edited().points().size() == 2, "Second automatic point must be 2");
            String originalId = WaCameraClient.edited().points().get(1).id();
            double x = mc.player.getX() + 3, y = mc.player.getEyeY(), z = mc.player.getZ() + 2;
            check(execute("wac cam 2 " + x + " " + y + " " + z + " 91 -12") == 2, "Explicit point number failed");
            destination = WaCameraClient.edited().points().get(1);
            check(destination.id().equals(originalId) && Math.abs(destination.x() - x) < 1e-8
                    && Math.abs(destination.y() - y) < 1e-8 && Math.abs(destination.z() - z) < 1e-8
                    && destination.yawDegrees() == 91 && destination.pitchDegrees() == -12, "Explicit coordinate/orientation replacement failed");
            check(execute("wac cam") == 3 && WaCameraClient.edited().points().size() == 3, "Automatic append after replacement must be 3");
            check(execute("wac cam 1") == 1 && WaCameraClient.edited().points().size() == 3, "Numbered current-view replacement failed");
            check(WaCameraClient.edited().points().get(0).id().equals(first.id()), "Replacement changed the point identity");
            ShotDefinition before = WaCameraClient.edited();
            rejected("wac cam 5");
            rejected("wac goto 64");
            rejected("wac cam 1 0 0 0 0 90.00000001");
            check(before.equals(WaCameraClient.edited()), "Rejected input mutated the shot");
            ShotDefinition other = new ShotDefinition(1, before.name(), "minecraft:the_nether", before.durationSeconds(),
                    before.countdownSeconds(), before.positionInterpolation(), before.speedMode(), before.lookMode(),
                    before.fovDegrees(), before.points(), before.target());
            WaCameraClient.setEdited(other);
            rejected("wac cam");
            rejected("wac goto 1");
            check(other.equals(WaCameraClient.edited()), "Dimension mismatch mutated the shot");
            WaCameraClient.setEdited(before);
            var originalPose = mc.player.getPose();
            try {
                mc.player.setPose(net.minecraft.world.entity.Pose.SWIMMING);
                rejected("wac goto 2");
            } finally { mc.player.setPose(originalPose); }
            check(execute("wac goto 2") == 1, "Teleport request was not submitted");
            deadline = System.nanoTime() + 8_000_000_000L;
            phase = 1;
            return false;
        }
        if (System.nanoTime() > deadline) throw new IllegalStateException("Server-authoritative goto did not reach its point and angles");
        if (phase == 1) {
            var p = mc.player;
            if (distance(p.getX(), p.getEyeY(), p.getZ(), destination) > .01
                    || Math.abs(net.minecraft.util.Mth.wrapDegrees(p.getYRot() - destination.yawDegrees())) > .01
                    || Math.abs(p.getXRot() - destination.pitchDegrees()) > .01) return false;
            var server = mc.getSingleplayerServer();
            var uuid = p.getUUID();
            serverCheck = server.submit(() -> {
                var serverPlayer = server.getPlayerList().getPlayer(uuid);
                return serverPlayer != null && distance(serverPlayer.getX(), serverPlayer.getEyeY(), serverPlayer.getZ(), destination) < .01
                        && Math.abs(net.minecraft.util.Mth.wrapDegrees(serverPlayer.getYRot() - destination.yawDegrees())) < .01
                        && Math.abs(serverPlayer.getXRot() - destination.pitchDegrees()) < .01;
            });
            phase = 2;
            return false;
        }
        if (!serverCheck.isDone()) return false;
        check(serverCheck.get(), "Goto moved only the client, not the server player");
        Files.createDirectories(WaCameraClient.root());
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(WaCameraClient.root().resolve("smoke-command-goto.png"));
        }
        Files.writeString(WaCameraClient.root().resolve("smoke-commands.txt"),
                "PASS: real client commands; automatic 1/2/3; current-view and explicit replacement; preserved IDs; rejected gaps, missing points, pitch overflow, dimension mismatch, nonstanding goto; server-authoritative goto XYZ/yaw/pitch\n");
        phase = 3;
        return true;
    }

    static void verifyBusy() throws Exception {
        ShotDefinition before = WaCameraClient.edited();
        rejected("wac cam");
        rejected("wac goto 1");
        check(before.equals(WaCameraClient.edited()), "Busy commands mutated the recording");
    }

    private static int execute(String command) throws Exception {
        return ClientCommandHandler.getDispatcher().execute(command, ClientCommandHandler.getSource());
    }
    private static void rejected(String command) throws Exception {
        try { if (execute(command) <= 0) return; }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException | net.minecraft.commands.CommandRuntimeException expected) { return; }
        throw new IllegalStateException("Command should have been rejected: " + command);
    }
    private static boolean sameView(ShotPoint a, ShotPoint b) {
        return distance(a.x(), a.y(), a.z(), b) < 1e-8
                && Math.abs(net.minecraft.util.Mth.wrapDegrees(a.yawDegrees() - b.yawDegrees())) < 1e-4
                && Math.abs(a.pitchDegrees() - b.pitchDegrees()) < 1e-4;
    }
    private static double distance(double x, double y, double z, ShotPoint p) {
        return Math.sqrt((x-p.x())*(x-p.x()) + (y-p.y())*(y-p.y()) + (z-p.z())*(z-p.z()));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
