package dev.tigr.ares.fabric.mixin.client;

import dev.tigr.ares.core.Ares;
import dev.tigr.ares.fabric.event.client.EntityEvent;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * @author Tigermouthbear
 */
@Mixin(ClientWorld.class)
public class MixinClientWorld {
    @Inject(method = "addEntity", at = @At("RETURN"))
    public void addEntity(int id, Entity entity, CallbackInfo ci) {
        Ares.EVENT_MANAGER.post(new EntityEvent.Spawn(entity));
    }

    @Inject(method = "finishRemovingEntity", at = @At("HEAD"))
    public void removeEntity(Entity entity, CallbackInfo ci) {
        Ares.EVENT_MANAGER.post(new EntityEvent.Remove(entity));
    }
}
