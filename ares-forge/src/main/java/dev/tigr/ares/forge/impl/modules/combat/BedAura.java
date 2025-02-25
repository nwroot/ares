package dev.tigr.ares.forge.impl.modules.combat;

import dev.tigr.ares.core.feature.FriendManager;
import dev.tigr.ares.core.feature.module.Category;
import dev.tigr.ares.core.feature.module.Module;
import dev.tigr.ares.core.setting.Setting;
import dev.tigr.ares.core.setting.settings.BooleanSetting;
import dev.tigr.ares.core.setting.settings.EnumSetting;
import dev.tigr.ares.core.setting.settings.numerical.DoubleSetting;
import dev.tigr.ares.core.setting.settings.numerical.FloatSetting;
import dev.tigr.ares.core.setting.settings.numerical.IntegerSetting;
import dev.tigr.ares.core.util.Pair;
import dev.tigr.ares.core.util.Timer;
import dev.tigr.ares.core.util.global.ReflectionHelper;
import dev.tigr.ares.core.util.global.Utils;
import dev.tigr.ares.forge.utils.*;
import dev.tigr.ares.forge.event.events.player.PacketEvent;
import dev.tigr.simpleevents.listener.EventHandler;
import dev.tigr.simpleevents.listener.EventListener;
import net.minecraft.block.BlockAir;
import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.item.ItemAppleGold;
import net.minecraft.item.ItemBed;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.CPacketHeldItemChange;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.*;
import net.minecraft.world.DimensionType;

import java.util.*;
import java.util.stream.Collectors;

import static dev.tigr.ares.forge.impl.modules.combat.CrystalAura.getDamage;
import static dev.tigr.ares.forge.impl.modules.combat.CrystalAura.rayTrace;

/**
 * @author Tigermouthbear 2/6/21
 */
@Module.Info(name = "BedAura", description = "Automatically places and explodes beds in the nether or end for combat", category = Category.COMBAT)
public class BedAura extends Module {
    // TODO: Simpler 1.15+ mode which focuses on placing on player rather than calculating. Better rotations. Antisuicide / max self damage. Smart Break? Automatic fire remover for 1.12 placements (can't place foot of bed on fire block)
    // TODO: Forge Only (from my testing) fix Bedaura consistently getting stuck trying to break bed if on 0 tick break delay
    private final Setting<Target> targetSetting = register(new EnumSetting<>("Target", Target.CLOSEST));
    private final Setting<Mode> placeMode = register(new EnumSetting<>("Place Mode", Mode.DAMAGE));
//    private final Setting<Boolean> preventSuicide = register(new BooleanSetting("Prevent Suicide", true));
    private final Setting<Boolean> noGappleSwitch = register(new BooleanSetting("No Gapple Switch", false));
    private final Setting<Integer> placeDelay = register(new IntegerSetting("Place Delay", 0, 0, 15));
    private final Setting<Integer> breakDelay = register(new IntegerSetting("Break Delay", 2, 0, 15));
    private final Setting<Float> minDamage = register(new FloatSetting("Minimum Damage", 7.5f, 0, 15));
    private final Setting<Double> placeRange = register(new DoubleSetting("Place Range", 5, 0, 10));
    private final Setting<Double> breakRange = register(new DoubleSetting("Break Range", 5, 0, 10));
    private final Setting<Integer> replenishSlot = register(new IntegerSetting("Replenish Slot", 8, 1, 9));
    private final Setting<Boolean> silentSwitch = register(new BooleanSetting("Silent Switch", true)).setVisibility(() -> Math.max(breakDelay.getValue(), placeDelay.getValue()) > 0);
    private final Setting<Boolean> sync = register(new BooleanSetting("Sync", true));
    private final Setting<Boolean> oneDotFifteen = register(new BooleanSetting("1.15", false));


    private final Setting<Boolean> showRenderOptions = register(new BooleanSetting("Show Render Options", false));
    private final Setting<Boolean> renderAir = register(new BooleanSetting("Render While Air", false));
    private final Setting<Float> colorRed = register(new FloatSetting("Red", 1, 0, 1)).setVisibility(showRenderOptions::getValue);
    private final Setting<Float> colorGreen = register(new FloatSetting("Green", 1, 0, 1)).setVisibility(showRenderOptions::getValue);
    private final Setting<Float> colorBlue = register(new FloatSetting("Blue", 0.45f, 0, 1)).setVisibility(showRenderOptions::getValue);
    private final Setting<Float> fillAlpha = register(new FloatSetting("Fill Alpha", 0.24f, 0, 1)).setVisibility(showRenderOptions::getValue);
    private final Setting<Float> boxAlpha = register(new FloatSetting("Line Alpha", 1f, 0, 1)).setVisibility(showRenderOptions::getValue);

    enum Mode { DAMAGE, DISTANCE }
    enum Target { CLOSEST, MOST_DAMAGE }

    private final Timer logicTimer = new Timer();
    private double[] rotations = null;
    public Pair<BlockPos, EnumFacing> target = null;
    private final Stack<BlockPos> placed = new Stack<>();

    private AxisAlignedBB renderBox = null;
    private final Timer renderTimer = new Timer();

    @Override
    public void onTick() {
        if(!MC.world.provider.getDimensionType().equals(DimensionType.OVERWORLD)) run();
    }

    private void run() {
        placed.removeIf(pos -> !(MC.world.getBlockState(pos).getBlock() instanceof BlockBed));

        // reset rotations
        if(rotations != null) rotations = null;

        // cleanup render
        if(renderTimer.passedSec(3)) {
            target = null;
        }

        // Check player has beds
        if(amountBedInInventory() <= 0 && placed.isEmpty()) return;

        // replenish
        replenishBed();

        if(amountBedInHotbar() <= 0 && placed.isEmpty()) return;

        // do logic
        place();
        explode();
    }

    private void place() {
        if(logicTimer.passedTicks(placeDelay.getValue()) && placed.isEmpty()) {
            // if no gapple switch and player is holding apple
            if(noGappleSwitch.getValue() && MC.player.inventory.getCurrentItem().getItem() instanceof ItemAppleGold) {
                if(target != null) target = null;
                return;
            }

            // find best crystal spot
            Pair<BlockPos, EnumFacing> target = getBestPlacement();
            if(target == null) return;

            placeBed(target);
            logicTimer.reset();
        }
    }

    private void placeBed(Pair<BlockPos, EnumFacing> pair) {
        int oldSelection = -1;
        if(silentSwitch.getValue() && Math.max(breakDelay.getValue(), placeDelay.getValue()) > 0)
            oldSelection = MC.player.inventory.currentItem;
        // switch to crystals if not holding
        if(!(MC.player.inventory.getCurrentItem().getItem() instanceof ItemBed)) {
            int slot = -1;
            for(int i = 0; i < 9; i++) {
                if(MC.player.inventory.getStackInSlot(i).getItem() instanceof ItemBed) {
                    slot = i;
                    break;
                }
            }
            if(slot != -1) {
                MC.player.inventory.currentItem = slot;
                if(sync.getValue()) MC.player.connection.sendPacket(new CPacketHeldItemChange());
            }
        }

        // place
        placeRotated(pair.getFirst(), pair.getSecond());
        placed.add(pair.getFirst());

        // Swap back
        if(silentSwitch.getValue() && Math.max(breakDelay.getValue(), placeDelay.getValue()) > 0 && oldSelection != -1)
            MC.player.inventory.currentItem = oldSelection;

        // set render pos
        target = pair;
    }

    private void placeRotated(BlockPos pos, EnumFacing direction) {
        float yaw = direction.getHorizontalAngle();
        MC.player.connection.sendPacket(new CPacketPlayer.Rotation(yaw, MC.player.rotationPitch, MC.player.onGround));
        WorldUtils.placeBlock(EnumHand.MAIN_HAND, pos, false, oneDotFifteen.getValue());
        rotations = new double[] { yaw, MC.player.rotationPitch };
    }

    private void explode() {
        if(!logicTimer.passedTicks(breakDelay.getValue()) || placed.isEmpty()) return;

        BlockPos pos = placed.peek();
        Vec3d vec = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        MC.playerController.processRightClickBlock(MC.player, MC.world, pos, EnumFacing.UP, vec, EnumHand.MAIN_HAND);

        if(!(MC.world.getBlockState(pos).getBlock() instanceof BlockBed)) {
            placed.remove(pos);
        }

        // spoof rotations
        rotations = WorldUtils.calculateLookAt(vec.x, vec.y, vec.z, MC.player);

        // reset timer
        logicTimer.reset();
    }

    @EventHandler
    public EventListener<PacketEvent.Sent> packetSentEvent = new EventListener<>(event -> {
        // rotation spoofing
        if(event.getPacket() instanceof CPacketPlayer && rotations != null) {
            ReflectionHelper.setPrivateValue(CPacketPlayer.class, event.getPacket(), (float) rotations[1], "pitch", "field_149473_f");
            ReflectionHelper.setPrivateValue(CPacketPlayer.class, event.getPacket(), (float) rotations[0], "yaw", "field_149476_e");
        }
    });

    // draw target
    @Override
    public void onRender3d() {
        if(target != null) {
            if(MC.world.getBlockState(target.getFirst()).getBlock() instanceof BlockBed) {
                switch (target.getSecond()) {
                    case NORTH:
                        renderBox = RenderUtils.getBoundingBox(target.getFirst()).expand(0, 0, 1).offset(0, 0, -1);
                        renderTimer.reset();
                        break;
                    case WEST:
                        renderBox = RenderUtils.getBoundingBox(target.getFirst()).expand(1, 0, 0).offset(-1, 0, 0);
                        renderTimer.reset();
                        break;
                    case SOUTH:
                        renderBox = RenderUtils.getBoundingBox(target.getFirst()).expand(0, 0, 1).offset(0, 0, 0);
                        renderTimer.reset();
                        break;
                    case EAST:
                        renderBox = RenderUtils.getBoundingBox(target.getFirst()).expand(1, 0, 0).offset(0, 0, 0);
                        renderTimer.reset();
                        break;
                }
            }
        }

        if(renderBox != null) {
            if(MC.world.getBlockState(new BlockPos(renderBox.minX, renderBox.minY, renderBox.minZ)).getBlock() instanceof BlockBed) {
                renderTimer.reset();
            } else if(!renderAir.getValue()) {
                renderBox = null;
                return;
            }
            if(renderAir.getValue() && renderTimer.passedTicks(placeDelay.getValue() +5)) {
                renderBox = null;
                return;
            }
            RenderUtils.prepare3d();
            RenderGlobal.renderFilledBox(renderBox, colorRed.getValue(), colorGreen.getValue(), colorBlue.getValue(), fillAlpha.getValue());
            RenderGlobal.drawSelectionBoundingBox(renderBox, colorRed.getValue(), colorGreen.getValue(), colorBlue.getValue(), boxAlpha.getValue());
            RenderUtils.end3d();
        }
    }

//    private boolean canBreakBed(Pair<BlockPos, EnumFacing> pair) {
//        return MC.player.getDistanceSq(pair.getFirst().getX(), pair.getFirst().getY(), pair.getFirst().getZ()) <= breakRange.getValue() * breakRange.getValue() // check range
//        && !(MC.player.getHealth() - getDamage(new Vec3d(pair.getFirst().getX() + 0.5 + pair.getSecond().getXOffset() / 2d, pair.getFirst().getY() + 0.5, pair.getFirst().getZ() + 0.5 + pair.getSecond().getZOffset() / 2d), MC.player) <= 1 && preventSuicide.getValue()); // check suicide
//    }

    private Pair<BlockPos, EnumFacing> getBestPlacement() {
        double bestScore = 69420;
        Pair<BlockPos, EnumFacing> target = null;
        for(EntityPlayer targetedPlayer: getTargets()) {
            // find best location to place
            List<BlockPos> targetsBlocks = getPlaceableBlocks(targetedPlayer);
            List<BlockPos> blocks = getPlaceableBlocks(MC.player);

            for(BlockPos pos: blocks) {
                if(!targetsBlocks.contains(pos) || (double) getDamage(new Vec3d(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5), targetedPlayer) < minDamage.getValue())
                    continue;

                double score = getScore(pos, targetedPlayer);

                // find best place for bed part
                if(target == null || (score < bestScore && score != -1)) {
                    // find direction to place
                    Pair<BlockPos, EnumFacing> placement = getBedPlacement(pos);
                    if(placement != null) {
                        target = placement;
                        bestScore = score;
                    }
                }
            }
        }
        return target;
    }

    private Pair<BlockPos, EnumFacing> getBedPlacement(BlockPos pos) {
        IBlockState north = MC.world.getBlockState(pos.north());
        IBlockState east = MC.world.getBlockState(pos.east());
        IBlockState south = MC.world.getBlockState(pos.south());
        IBlockState west = MC.world.getBlockState(pos.west());

        if((oneDotFifteen.getValue() ? north.getMaterial().isReplaceable() : north.getBlock() instanceof BlockAir)
                && MC.world.getEntitiesWithinAABB(Entity.class, new AxisAlignedBB(pos.north())).stream().noneMatch(Entity::canBeCollidedWith)) {
            if(oneDotFifteen.getValue() || MC.world.getBlockState(pos.down()).isFullCube()
                    && MC.world.getBlockState(pos.north().down()).isFullCube())
                return new Pair<>(pos.north(), EnumFacing.SOUTH);
        }

        if((oneDotFifteen.getValue() ? east.getMaterial().isReplaceable() : east.getBlock() instanceof BlockAir)
                && MC.world.getEntitiesWithinAABB(Entity.class, new AxisAlignedBB(pos.east())).stream().noneMatch(Entity::canBeCollidedWith)) {
            if(oneDotFifteen.getValue() || MC.world.getBlockState(pos.down()).isFullCube()
                    && MC.world.getBlockState(pos.east().down()).isFullCube())
                return new Pair<>(pos.east(), EnumFacing.WEST);
        }

        if((oneDotFifteen.getValue() ? south.getMaterial().isReplaceable() : south.getBlock() instanceof BlockAir)
                && MC.world.getEntitiesWithinAABB(Entity.class, new AxisAlignedBB(pos.south())).stream().noneMatch(Entity::canBeCollidedWith)) {
            if(oneDotFifteen.getValue() || MC.world.getBlockState(pos.down()).isFullCube()
                    && MC.world.getBlockState(pos.south().down()).isFullCube())
                return new Pair<>(pos.south(), EnumFacing.NORTH);
        }

        if((oneDotFifteen.getValue() ? west.getMaterial().isReplaceable() : west.getBlock() instanceof BlockAir)
                && MC.world.getEntitiesWithinAABB(Entity.class, new AxisAlignedBB(pos.west())).stream().noneMatch(Entity::canBeCollidedWith)) {
            if(oneDotFifteen.getValue() || MC.world.getBlockState(pos.down()).isFullCube()
                    && MC.world.getBlockState(pos.west().down()).isFullCube())
                return new Pair<>(pos.west(), EnumFacing.EAST);
        }

        return null;
    }

    // utils
    private double getScore(BlockPos pos, EntityPlayer player) {
        double score;
        if(placeMode.getValue() == Mode.DISTANCE) {
            score = Math.abs(player.posY - pos.up().getY())
                    + Math.abs(player.posX - pos.getX())
                    + Math.abs(player.posZ - pos.getZ());

            if(rayTrace(
                    new Vec3d(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5),
                    new Vec3d(player.getPositionVector().x,
                            player.getPositionVector().y,
                            player.getPositionVector().z))

                    == RayTraceResult.Type.BLOCK) score = -1;
        } else {
            score = 200 - getDamage(new Vec3d(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5), player);
        }

        return score;
    }

    private List<EntityPlayer> getTargets() {
        List<EntityPlayer> targets = new ArrayList<>();

        if(targetSetting.getValue() == Target.CLOSEST) {
            targets.addAll(MC.world.playerEntities.stream().filter(this::isValidTarget).collect(Collectors.toList()));
            targets.sort(Comparators.entityDistance);
        } else if(targetSetting.getValue() == Target.MOST_DAMAGE) {
            for(EntityPlayer entityPlayer: MC.world.playerEntities) {
                if (!isValidTarget(entityPlayer))
                    continue;
                targets.add(entityPlayer);
            }
        }

        return targets;
    }

    private boolean isValidTarget(EntityPlayer player) {
        return !FriendManager.isFriend(player.getGameProfile().getName())
                && !player.isDead
                && !(player.getHealth() <= 0)
                && !(MC.player.getDistance(player) > Math.max(placeRange.getValue(), breakRange.getValue()) + 8)
                && player != MC.player;
    }

    private List<BlockPos> getPlaceableBlocks(Entity player) {
        List<BlockPos> square = new ArrayList<>();

        int range = (int) Utils.roundDouble(placeRange.getValue(), 0);
        BlockPos pos = player.getPosition();
        for(int x = -range; x <= range; x++)
            for(int y = -range; y <= range; y++)
                for(int z = -range; z <= range; z++)
                    square.add(pos.add(x, y, z));

        return square.stream().filter(blockPos -> canBedBePlacedHere(blockPos) && MC.player.getDistanceSq(blockPos.getX() + 0.5, blockPos.getY(), blockPos.getZ() + 0.5) <= (range * range)).collect(Collectors.toList());
    }

    private boolean canBedBePlacedHere(BlockPos pos) {
        if(!oneDotFifteen.getValue()) {
            return (MC.world.getBlockState(pos).getMaterial().isReplaceable() && MC.world.getBlockState(pos.down()).isFullCube())
                    && (MC.world.getBlockState(pos.north()).getBlock() instanceof BlockAir && MC.world.getBlockState(pos.north().down()).isFullCube()
                    || MC.world.getBlockState(pos.east()).getBlock() instanceof BlockAir && MC.world.getBlockState(pos.east().down()).isFullCube()
                    || MC.world.getBlockState(pos.south()).getBlock() instanceof BlockAir && MC.world.getBlockState(pos.south().down()).isFullCube()
                    || MC.world.getBlockState(pos.west()).getBlock() instanceof BlockAir && MC.world.getBlockState(pos.west().down()).isFullCube()
            );
        } else {
            return MC.world.getBlockState(pos).getMaterial().isReplaceable()
                    && (MC.world.getBlockState(pos.north()).getMaterial().isReplaceable()
                    || MC.world.getBlockState(pos.east()).getMaterial().isReplaceable()
                    || MC.world.getBlockState(pos.south()).getMaterial().isReplaceable()
                    || MC.world.getBlockState(pos.west()).getMaterial().isReplaceable()
            );
        }
    }

    private int amountBedInInventory() {
        int quantity = 0;

        for(int i = 0; i <= 44; i++) {
            ItemStack stackInSlot = MC.player.inventory.getStackInSlot(i);
            if(stackInSlot.getItem() instanceof ItemBed) quantity += stackInSlot.getCount();
        }

        return quantity;
    }

    private int amountBedInHotbar() {
        int quantity = 0;

        for(int i = 0; i < 9; i++) {
            ItemStack stackInSlot = MC.player.inventory.getStackInSlot(i);
            if(stackInSlot.getItem() instanceof ItemBed) quantity += stackInSlot.getCount();
        }

        return quantity;
    }

    private void replenishBed() {
        int slot = replenishSlot.getValue() - 1;
        if(MC.player.inventory.getStackInSlot(slot).getItem() instanceof ItemBed) return;
        if(MC.currentScreen == null || MC.currentScreen instanceof GuiContainer) {
            for(int i = 45; i > 8; i--) {
                if(MC.player.inventory.getStackInSlot(i).getItem() instanceof ItemBed) {
                    if(InventoryUtils.getHotbarBlank() != slot) {
                        if(MC.player.inventory.getStackInSlot(slot).isEmpty()) {
                            MC.playerController.windowClick(MC.player.inventoryContainer.windowId, InventoryUtils.getSlotIndex(i), 0, ClickType.PICKUP, MC.player);
                            MC.playerController.windowClick(MC.player.inventoryContainer.windowId, InventoryUtils.getSlotIndex(slot), 0, ClickType.PICKUP, MC.player);
                        } else {
                            MC.playerController.windowClick(MC.player.inventoryContainer.windowId, InventoryUtils.getSlotIndex(i), 0, ClickType.PICKUP, MC.player);
                            MC.playerController.windowClick(MC.player.inventoryContainer.windowId, InventoryUtils.getSlotIndex(slot), 0, ClickType.PICKUP, MC.player);
                            MC.playerController.windowClick(MC.player.inventoryContainer.windowId, InventoryUtils.getSlotIndex(i), 0, ClickType.PICKUP, MC.player); // return any items that may have been there to i
                        }
                    } else {
                        MC.playerController.windowClick(MC.player.inventoryContainer.windowId, InventoryUtils.getSlotIndex(i), 0, ClickType.QUICK_MOVE, MC.player);
                    }
                    return;
                }
            }
        }
    }
}
