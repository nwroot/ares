package dev.tigr.ares.fabric.impl.modules.player;

import dev.tigr.ares.core.feature.module.Category;
import dev.tigr.ares.core.feature.module.Module;
import dev.tigr.ares.core.setting.Setting;
import dev.tigr.ares.core.setting.settings.BooleanSetting;
import dev.tigr.ares.core.setting.settings.numerical.IntegerSetting;
import dev.tigr.ares.core.util.render.TextColor;
import dev.tigr.ares.fabric.event.movement.PlayerJumpEvent;
import dev.tigr.ares.fabric.event.movement.WalkOffLedgeEvent;
import dev.tigr.ares.fabric.utils.InventoryUtils;
import dev.tigr.ares.core.util.Timer;
import dev.tigr.ares.fabric.utils.WorldUtils;
import dev.tigr.simpleevents.listener.EventHandler;
import dev.tigr.simpleevents.listener.EventListener;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;

/**
 * @author Tigermouthbear
 * ported to Fabric by Hoosiers
 * Tower added by Makrennel 3/31/21
 */
@Module.Info(name = "Scaffold", description = "Automatically bridges for you", category = Category.PLAYER)
public class Scaffold extends Module {
    private final Setting<Integer> radius = register(new IntegerSetting("Radius", 0, 0, 6));
    private final Setting<Boolean> rotate = register(new BooleanSetting("Rotate", true));
    private final Setting<Boolean> down = register(new BooleanSetting("Down", false));
    private final Setting<Boolean> tower = register(new BooleanSetting("Tower", true)).setVisibility(() -> radius.getValue() <= 0);
    private final Setting<Boolean> packetTower = register(new BooleanSetting("Packet Tower", false)).setVisibility(() -> tower.getValue() && radius.getValue() <= 0);
    private final Setting<Integer> towerJumpVelocity = register(new IntegerSetting("Jump Velocity", 42, 37, 60)).setVisibility(() -> tower.getValue() && radius.getValue() <= 0 && !packetTower.getValue());
    private final Setting<Integer> towerReturnVelocity = register(new IntegerSetting("Return Velocity", 30, 20, 60)).setVisibility(() -> tower.getValue() && radius.getValue() <= 0 && !packetTower.getValue());
    private final Setting<Integer> towerClipDelay = register(new IntegerSetting("Clip Delay", 128, 1, 500)).setVisibility(() -> tower.getValue() && radius.getValue() <= 0 && packetTower.getValue());
    private final Setting<Boolean> towerReturnPacket = register(new BooleanSetting("Packet Return To Ground", false)).setVisibility(() -> tower.getValue() && radius.getValue() <= 0 && packetTower.getValue());
    private final Setting<Integer> returnDelay = register(new IntegerSetting("Return Delay", 7, 0, 12)).setVisibility(() -> tower.getValue() && radius.getValue() <= 0 && packetTower.getValue() && towerReturnPacket.getValue());

    private Timer towerDelayTimer = new Timer();
    private boolean shouldResetTower;

    @EventHandler
    public EventListener<WalkOffLedgeEvent> walkOffLedgeEvent = new EventListener<>(event -> {
        if(!down.getValue() && !MC.player.isSprinting()) event.isSneaking = true;
    });

    @EventHandler
    public EventListener<PlayerJumpEvent> onPlayerJumpEvent = new EventListener<>(event -> {
        if(tower.getValue() && radius.getValue() <= 0) {
            event.setCancelled(true);
        }
    });

    @Override
    public void onTick() {
        if(tower.getValue() && MC.options.keyJump.isPressed() && radius.getValue() <= 0 && !packetTower.getValue()) {
            if(MC.player.isOnGround())
                MC.player.setVelocity(MC.player.getVelocity().x *0.3,towerJumpVelocity.getValue().doubleValue() /100, MC.player.getVelocity().z *0.3);
            if(MC.world.getBlockState(MC.player.getBlockPos().down()).isAir())
                MC.player.setVelocity(MC.player.getVelocity().x *0.3, -(towerReturnVelocity.getValue().doubleValue() /100), MC.player.getVelocity().z *0.3);
        }
        if(tower.getValue() && packetTower.getValue() && MC.options.keyJump.isPressed() && radius.getValue() <= 0) {
            if(shouldResetTower) {
                towerDelayTimer.reset();
                shouldResetTower = false;
            }
            if(!MC.player.isOnGround()) {
                if (!MC.world.getBlockState(WorldUtils.roundBlockPos(MC.player.getPos()).down()).isAir()
                        && towerReturnPacket.getValue()
                        && towerDelayTimer.passedMillis(returnDelay.getValue())) {
                    MC.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionOnly(MC.player.getX(), Math.floor(MC.player.getY()), MC.player.getZ(), true));
                    MC.player.updatePosition(MC.player.getX(), Math.floor(MC.player.getY()), MC.player.getZ());
                }
                shouldResetTower = true;
                return;
            }
            if(towerDelayTimer.passedMillis(towerClipDelay.getValue())) {
                // Pretend that we are jumping to the server and then update player position to meet where the server thinks the player is instantly.
                WorldUtils.fakeJump();
                MC.player.updatePosition(MC.player.getX(), MC.player.getY() + 1.15, MC.player.getZ());
                shouldResetTower = true;
            }
        }
    }

    @Override
    public void onMotion() {
        int oldSlot = MC.player.inventory.selectedSlot;
        int newSlot = InventoryUtils.getBlockInHotbar();

        if(newSlot != -1) {
            MC.player.inventory.selectedSlot = newSlot;
        } else {
            UTILS.printMessage(TextColor.RED + "No blocks found in hotbar!");
            setEnabled(false);
            return;
        }

        //Down
        if(radius.getValue() != 0 && down.getValue()) radius.setValue(0);

        if(MC.options.keySprint.isPressed() && down.getValue()) {
            float yaw = (float) Math.toRadians(MC.player.yaw);
            double yVelocity = MC.player.getVelocity().y;

            if(MC.options.keyForward.isPressed()) {
                MC.player.setVelocity(-MathHelper.cos(yaw) * 0.03f, yVelocity, MathHelper.sin(yaw) * 0.03f);
            }
            if(MC.options.keyBack.isPressed()) {
                MC.player.setVelocity(MathHelper.cos(yaw) * 0.03f, yVelocity, -MathHelper.sin(yaw) * 0.03f);
            }
            if(MC.options.keyLeft.isPressed()) {
                MC.player.setVelocity(MathHelper.cos(yaw) * 0.03f, yVelocity, MathHelper.sin(yaw) * 0.03f);
            }
            if(MC.options.keyRight.isPressed()) {
                MC.player.setVelocity(-MathHelper.cos(yaw) * 0.03f, yVelocity, -MathHelper.sin(yaw) * 0.03f);
            }

            BlockPos under = new BlockPos(MC.player.getX(), MC.player.getY() - 2, MC.player.getZ());

            if(MC.world.getBlockState(under).getMaterial().isReplaceable()) WorldUtils.placeBlockMainHand(under, rotate.getValue());

            MC.player.inventory.selectedSlot = oldSlot;

            return;
        }

        //Radius = 0
        if(radius.getValue() == 0) {
            BlockPos under = new BlockPos(MC.player.getX(), MC.player.getY() - 1, MC.player.getZ());

            if(MC.world.getBlockState(under).getMaterial().isReplaceable()) WorldUtils.placeBlockMainHand(under, rotate.getValue());

            MC.player.inventory.selectedSlot = oldSlot;

            return;
        }

        //Radius > 0
        ArrayList<BlockPos> blocks = new ArrayList<>();
        for(int x = -radius.getValue(); x <= radius.getValue(); x++) {
            for(int z = -radius.getValue(); z <= radius.getValue(); z++) {
                blocks.add(new BlockPos(MC.player.getX() + x, MC.player.getY() - 1, MC.player.getZ() + z));
            }
        }

        for(BlockPos x: blocks) {
            if(MC.world.getBlockState(x).getMaterial().isReplaceable()) {
                WorldUtils.placeBlockMainHand(x, rotate.getValue());
                break;
            }
        }

        MC.player.inventory.selectedSlot = oldSlot;
    }
}
