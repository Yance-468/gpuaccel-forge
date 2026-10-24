package dev.gpuaccel.client;

import dev.gpuaccel.GpuAcceleratorMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Client-only keybinding and input handling for the GPU status screen. */
public final class ClientModEvents {
    public static final KeyMapping OPEN_GUI = new KeyMapping(
            "key.gpuaccel.open",
            GLFW.GLFW_KEY_G,
            "key.categories.gpuaccel"
    );

    private ClientModEvents() {}

    @Mod.EventBusSubscriber(value = Dist.CLIENT, modid = GpuAcceleratorMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        private ModBus() {}

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(OPEN_GUI);
        }
    }

    @Mod.EventBusSubscriber(value = Dist.CLIENT, modid = GpuAcceleratorMod.MODID)
    public static final class ForgeBus {
        private ForgeBus() {}

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                while (OPEN_GUI.consumeClick()) {
                    Minecraft.getInstance().setScreen(new GpuStatusScreen());
                }
            }
        }
    }
}
