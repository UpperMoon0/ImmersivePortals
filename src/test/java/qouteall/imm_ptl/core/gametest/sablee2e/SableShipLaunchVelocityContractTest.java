package qouteall.imm_ptl.core.gametest.sablee2e;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Source contract usable before mapped Minecraft dependencies are available locally. */
class SableShipLaunchVelocityContractTest {
    private static String source() throws Exception {
        return Files.readString(Path.of("src/main/java/qouteall/imm_ptl/core/gametest/sablee2e/SableShipFlightTest.java"));
    }

    @Test void nativeDeltaCannotAliasTheCallersAbsoluteVelocity() throws Exception {
        String source=source();
        int start=source.indexOf("private static void setVelocity(");
        String setter=source.substring(start,source.indexOf("private static void cleanup()",start));
        assertTrue(setter.contains("ServerSubLevel body, Vector3dc velocity"),
            "The requested absolute velocity must be a read-only input");
        assertTrue(setter.contains("new Vector3d(velocity).sub(handle.getLinearVelocity(new Vector3d()))"),
            "Subtraction must target an independent delta vector, including nonzero observation-hold velocity");
        assertFalse(setter.contains("handle.addLinearAndAngularVelocity(velocity.sub("));
        assertTrue(setter.contains("require(!broadsideInFlight"),"Do not weaken the no-write flight guard");
    }

    @Test void launchReceiptReadsActualNativeVelocityBeforeTheReadOnlyFlightInterval() throws Exception {
        String source=source();
        int start=source.indexOf("private static void launchBroadside(");
        String launch=source.substring(start,source.indexOf("private static SableShipBroadsideGeometry.Bounds broadsideBounds(",start));
        int write=launch.indexOf("setVelocity(body,new Vector3d(velocity))");
        int read=launch.indexOf("actualVelocity=handle(body).getLinearVelocity(new Vector3d())");
        int receipt=launch.indexOf("initial_velocity\",List.of(actualVelocity.x,actualVelocity.y,actualVelocity.z)");
        int guard=launch.indexOf("broadsideInFlight=true");
        assertTrue(write>=0 && read>write && receipt>read && guard>receipt);
        assertTrue(launch.contains("actualVelocity.distance(velocity)<1e-6"),
            "The measured absolute velocity must match the target, not its delta impulse");
        assertTrue(launch.indexOf("recordBroadsideSample(body,false)")>guard);
    }
}
