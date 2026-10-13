package qouteall.imm_ptl.core.gametest.sablee2e;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.fml.ModList;
import org.joml.Vector3d;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Optional acceptance-test actuator for the official Aeronautics 1.3.1 bundle.
 * Only ordinary Create motor controls are written. No native velocity, force,
 * pose, gravity, air pressure, or addon configuration is changed here.
 *
 * The large ship gets 252 symmetric motor/shaft/wooden-propeller drive trains.
 * Three-block spacing leaves the radius-1.5 rotors tangent without overlapping.
 * All 756 added blocks fit inside the existing 49 x 17 x 65 fixture envelope.
 * The control ship uses eight engines and replaces selected hull fittings.
 */
public final class SableAeronauticsShipDrive {
    public static final String VERSION = "1.3.1";
    public static final String ARTIFACT_SHA256 = "b531dbe1081ee354f6e47ba4e0443b9caa648dfb8c82013a591a20836245bd84";
    private static final String PROPELLER_TYPE =
        "dev.eriksonn.aeronautics.content.blocks.propeller.small.BasePropellerBlockEntity";

    public record MotionSample(UUID shipId, String dimension, long gameTime,
        double x, double y, double z, double vx, double vy, double vz) {}

    private final List<BlockPos> motors;
    private final Map<BlockPos, BlockState> additions;
    private final Class<?> propellerClass;
    private final Method getThrust;
    private final Method getScaledThrust;
    private final Method isActive;
    private final Method getAngle;
    private final Method getPressure;
    private final Method getConfigThrust;
    private final Method getGravity;
    private Map<BlockPos, Integer> commandedRpms = Map.of();
    private int nominalMotorRpm;

    private record TrimPlan(Map<BlockPos, Integer> rpms, double force, double minimumPressure,
        double momentX, double momentZ) {}

    private SableAeronauticsShipDrive(boolean large) {
        for (String mod : List.of("simulated", "aeronautics", "offroad")) {
            require(ModList.get().isLoaded(mod), "Optional powered-flight test requires " + mod);
            String version = ModList.get().getModContainerById(mod).orElseThrow()
                .getModInfo().getVersion().toString();
            require(VERSION.equals(version), "Powered-flight artifact mismatch for " + mod + ": " + version);
        }
        try {
            propellerClass = Class.forName(PROPELLER_TYPE);
            getThrust = propellerClass.getMethod("getThrust");
            getScaledThrust = propellerClass.getMethod("getScaledThrust");
            isActive = propellerClass.getMethod("isActive");
            getAngle = propellerClass.getMethod("getAngle");
            getPressure = propellerClass.getMethod("getCurrentAirPressure");
            getConfigThrust = propellerClass.getMethod("getConfigThrust");
            getGravity = Class.forName("dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData")
                .getMethod("getGravity", Level.class);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Pinned Aeronautics/Sable propulsion API changed", error);
        }
        var placed = new LinkedHashMap<BlockPos, BlockState>();
        var motorPositions = new ArrayList<BlockPos>();
        BlockState motor = state("create:creative_motor").setValue(BlockStateProperties.FACING, Direction.UP);
        BlockState shaft = state("create:shaft").setValue(BlockStateProperties.AXIS, Direction.Axis.Y);
        BlockState propeller = state("aeronautics:wooden_propeller").setValue(BlockStateProperties.FACING, Direction.UP);
        if (large) {
            for (int x = -21; x <= 21; x += 3) {
                if (x == 0) continue; // Keep the rider, update panel, drain and roof spine unobstructed.
                for (int z = -27; z <= 27; z += 3) {
                    if (z == 0) continue;
                    motorPositions.add(new BlockPos(x, 1, z));
                }
            }
        } else {
            for (int x : new int[] {-3, 0, 3}) for (int z : new int[] {-4, 0, 4}) {
                if (x == 0 && z == 0) continue;
                // Raised centerline engines preserve the drain's solid output stop.
                motorPositions.add(new BlockPos(x, x == 0 ? 2 : 1, z));
            }
            placed.put(new BlockPos(0, 1, -4), state("minecraft:spruce_planks"));
        }
        for (BlockPos position : motorPositions) {
            placed.put(position, motor);
            placed.put(position.above(), shaft);
            placed.put(position.above(2), propeller);
        }
        motors = List.copyOf(motorPositions);
        additions = Collections.unmodifiableMap(placed);
        require(motors.size() == (large ? 252 : 8) && additions.size() == (large ? 756 : 25),
            "Unexpected powered-flight fixture size");
    }

    public static SableAeronauticsShipDrive create() { return create(true); }
    public static SableAeronauticsShipDrive create(boolean large) { return new SableAeronauticsShipDrive(large); }
    public int engineCount() { return motors.size(); }
    public List<BlockPos> motorPositions() { return motors; }
    public Map<BlockPos, BlockState> additionalBlocks() { return additions; }
    public Map<BlockPos, Integer> commandedMotorRpms() { return commandedRpms; }

    /** Read actual runtime mass, gravity and local atmosphere, without overriding any of them. */
    public Map<String, Object> liftBudget(ServerLevel level, ServerSubLevel body) {
        double mass = body.getSelfMassTracker().getMass();
        double gravityY;
        try {
            gravityY = ((Vector3d) getGravity.invoke(null, level)).y;
        } catch (IllegalAccessException | InvocationTargetException error) {
            throw new IllegalStateException("Could not read native dimension gravity", error);
        }
        require(Double.isFinite(mass) && mass > 0 && gravityY < 0, "Invalid native mass/gravity for lift witness");
        TrimPlan trim = trimPlan(level, body, -256);
        double maximumStaticLift = trim.force();
        double weight = mass * -gravityY;
        return Map.of("mass", mass, "gravityY", gravityY, "weight", weight,
            "maximumStaticLift", maximumStaticLift, "staticLiftWeightRatio", maximumStaticLift / weight,
            "minimumAirPressure", trim.minimumPressure(), "engineCount", motors.size(),
            "atmosphereModified", false, "trimMomentX", trim.momentX(), "trimMomentZ", trim.momentZ());
    }

    /** Positive means upward thrust, negative downward; zero lets the actual propellers spool down. */
    public void setVerticalCommand(ServerLevel level, ServerSubLevel body, int signedUpRpm) {
        require(signedUpRpm >= -256 && signedUpRpm <= 256, "Vertical RPM command outside [-256,256]");
        // Sable BlockEntityPropeller.getScaledThrust negates the raw propeller thrust.
        // Thus an UP-facing propeller must turn at negative RPM to accelerate upward.
        setRpm(level, body, -signedUpRpm);
    }

    /** Low-level real motor control; prefer setVerticalCommand to avoid thrust-sign ambiguity. */
    public void setRpm(ServerLevel level, ServerSubLevel body, int rpm) {
        require(rpm >= -256 && rpm <= 256, "Motor RPM outside [-256,256]");
        var commands = new LinkedHashMap<BlockPos, Integer>();
        if (rpm == 0) motors.forEach(position -> commands.put(position, 0));
        else commands.putAll(trimPlan(level, body, rpm).rpms());
        for (BlockPos position : motors) {
            var entity = level.getBlockEntity(at(body, position));
            require(entity instanceof CreativeMotorBlockEntity, "Powered-flight motor missing at " + position);
            ((CreativeMotorBlockEntity) entity).generatedSpeed.setValue(commands.get(position));
        }
        commandedRpms = Collections.unmodifiableMap(commands);
        nominalMotorRpm = rpm;
    }

    private TrimPlan trimPlan(ServerLevel level, ServerSubLevel body, int signedRpm) {
        var center = body.getSelfMassTracker().getCenterOfMass();
        require(center != null, "Cannot trim a ship without a real center of mass");
        int count = motors.size();
        double[] x = new double[count], z = new double[count], coefficients = new double[count];
        double minimumPressure = Double.POSITIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            BlockPos position = at(body, motors.get(i).above(2));
            x[i] = position.getX() + 0.5;
            z[i] = position.getZ() + 0.5;
            Object propeller = level.getBlockEntity(position);
            require(propellerClass.isInstance(propeller), "Propeller absent during real center-of-mass trim");
            double pressure = number(getPressure, propeller);
            require(pressure > 0, "Real atmosphere cannot support trimmed flight at zero air pressure");
            coefficients[i] = pressure * number(getConfigThrust, propeller);
            minimumPressure = Math.min(minimumPressure, pressure);
        }
        int[] values = SableShipThrustTrim.balanceMotorRpms(x, z, coefficients, center.x(), center.z(), Math.abs(signedRpm));
        var commands = new LinkedHashMap<BlockPos, Integer>();
        double force = 0, momentX = 0, momentZ = 0;
        for (int i = 0; i < count; i++) {
            commands.put(motors.get(i), signedRpm < 0 ? -values[i] : values[i]);
            double engineForce = values[i] * coefficients[i];
            force += engineForce;
            momentX += engineForce * (x[i] - center.x());
            momentZ += engineForce * (z[i] - center.z());
        }
        require(force > 0, "Trimmed propellers cannot provide any real lift");
        require(Math.hypot(momentX, momentZ) / force < 0.02,
            "Integer motor controls cannot balance the ship's actual center of mass");
        return new TrimPlan(Collections.unmodifiableMap(commands), force, minimumPressure, momentX, momentZ);
    }

    /**
     * Read-only server/client witness. Call after at least 40 ordinary spool-up ticks.
     * Every motor, shaft and propeller must run at the requested real kinetic speed.
     */
    public Map<String, Object> verifyThrust(Level level, SubLevel body, int motorRpm) {
        require(motorRpm != 0 && motorRpm >= -256 && motorRpm <= 256, "Thrust witness requires a nonzero valid RPM");
        int active = 0;
        double thrust = 0, scaledThrust = 0, minimumPressure = Double.POSITIVE_INFINITY;
        double angleChecksum = 0;
        for (BlockPos motor : motors) {
            var motorEntity = level.getBlockEntity(at(body, motor));
            require(motorEntity instanceof CreativeMotorBlockEntity, "Powered-flight motor missing at " + motor);
            float expectedSpeed = ((CreativeMotorBlockEntity) motorEntity).getSpeed();
            require(Float.isFinite(expectedSpeed) && Math.signum(expectedSpeed) == Math.signum(motorRpm)
                && Math.abs(expectedSpeed) >= 1 && Math.abs(expectedSpeed) <= Math.abs(motorRpm),
                "Trimmed motor has wrong direction/range at " + motor + ": " + expectedSpeed);
            if (level instanceof ServerLevel) {
                require(nominalMotorRpm == motorRpm && commandedRpms.containsKey(motor)
                    && Math.abs(expectedSpeed - commandedRpms.get(motor)) < 0.01,
                    "Trimmed motor does not match its real command at " + motor);
            }
            for (int dy = 0; dy < 3; dy++) {
                var entity = level.getBlockEntity(at(body, motor.above(dy)));
                require(entity instanceof KineticBlockEntity, "Powered-flight kinetic block missing at " + motor.above(dy));
                float speed = ((KineticBlockEntity) entity).getSpeed();
                require(Float.isFinite(speed) && Math.abs(speed - expectedSpeed) < 0.01,
                    "Powered-flight kinetic speed mismatch at " + motor.above(dy) + ": " + speed);
            }
            Object propeller = level.getBlockEntity(at(body, motor.above(2)));
            require(propellerClass.isInstance(propeller), "Expected real Aeronautics propeller at " + motor.above(2));
            if ((Boolean) invoke(isActive, propeller)) active++;
            double raw = number(getThrust, propeller);
            double scaled = number(getScaledThrust, propeller);
            double pressure = number(getPressure, propeller);
            require(Math.signum(raw) == Math.signum(expectedSpeed), "Propeller has not reversed to requested thrust direction");
            // Airflow scaling can legitimately tend to zero near terminal speed. Raw thrust
            // and motor motion must still be nonzero; report actual scaled native force.
            require(scaled * motorRpm <= 0 && pressure > 0, "Invalid native propeller pressure/thrust sign");
            thrust += raw;
            scaledThrust += scaled;
            minimumPressure = Math.min(minimumPressure, pressure);
            angleChecksum += number(getAngle, propeller);
        }
        require(active == motors.size(), "Not all real propellers are active");
        return Map.of("engineCount", motors.size(), "activePropellers", active, "motorRpm", motorRpm,
            "rawThrust", thrust, "scaledThrust", scaledThrust, "minimumAirPressure", minimumPressure,
            "angleChecksum", angleChecksum, "addonVersion", VERSION,
            "propulsion", "Aeronautics wooden propeller actors driven by Create motors");
    }

    public MotionSample sample(ServerSubLevel body) {
        var handle = RigidBodyHandle.of(body);
        require(handle != null && handle.isValid(), "Powered-flight native body missing");
        Vector3d velocity = handle.getLinearVelocity(new Vector3d());
        Vector3d position = new Vector3d(body.logicalPose().position());
        require(position.isFinite() && velocity.isFinite(), "Non-finite powered-flight body state");
        return new MotionSample(body.getUniqueId(), body.getLevel().dimension().location().toString(),
            body.getLevel().getGameTime(), position.x, position.y, position.z, velocity.x, velocity.y, velocity.z);
    }

    /**
     * Use an entirely unassisted interval in one dimension before/after a seam.
     * The runner must not write native velocity/forces during that interval.
     * An upward interval proves acceleration and movement against gravity.
     */
    public Map<String, Object> verifyPoweredMotion(MotionSample before, ServerSubLevel body, int direction) {
        require(direction == -1 || direction == 1, "Powered motion direction must be -1 or +1");
        MotionSample after = sample(body);
        require(before.shipId().equals(after.shipId()) && before.dimension().equals(after.dimension()),
            "Powered motion witness must sample the same ship within one dimension");
        long elapsed = after.gameTime() - before.gameTime();
        double displacement = after.y() - before.y();
        double velocityChange = after.vy() - before.vy();
        require(elapsed >= 5, "Powered motion witness interval is too short");
        require(direction * displacement > 0.05 && direction * velocityChange > 0.05,
            "Actual propellers did not accelerate and move the ship in the commanded direction: dy="
                + displacement + " dvy=" + velocityChange);
        return Map.of("ticks", elapsed, "verticalDisplacement", displacement,
            "verticalVelocityChange", velocityChange, "startVerticalVelocity", before.vy(),
            "endVerticalVelocity", after.vy(), "dimension", after.dimension(),
            "againstGravity", direction > 0);
    }

    private static BlockPos at(SubLevel body, BlockPos relative) { return body.getPlot().getCenterBlock().offset(relative); }

    private static BlockState state(String name) {
        ResourceLocation id = ResourceLocation.parse(name);
        require(BuiltInRegistries.BLOCK.containsKey(id), "Powered-flight block is unavailable: " + name);
        return BuiltInRegistries.BLOCK.get(id).defaultBlockState();
    }

    private static Object invoke(Method method, Object target) {
        try {
            return method.invoke(target);
        } catch (IllegalAccessException | InvocationTargetException error) {
            throw new IllegalStateException("Pinned propulsion API call failed: " + method.getName(), error);
        }
    }

    private static double number(Method method, Object target) {
        double value = ((Number) invoke(method, target)).doubleValue();
        require(Double.isFinite(value), "Non-finite propulsion evidence: " + method.getName());
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
