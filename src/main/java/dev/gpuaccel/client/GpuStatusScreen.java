package dev.gpuaccel.client;

import dev.gpuaccel.network.C2SGpuStatusRequest;
import dev.gpuaccel.network.GpuNetwork;
import dev.gpuaccel.network.GpuStatusHolder;
import dev.gpuaccel.network.GpuStatusSnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Locale;

/** Client screen showing the server's GPU usage / status. */
@OnlyIn(Dist.CLIENT)
public class GpuStatusScreen extends Screen {

    public GpuStatusScreen() {
        super(Component.literal("GPU 状态 / GPU Status"));
    }

    @Override
    protected void init() {
        GpuNetwork.CHANNEL.sendToServer(new C2SGpuStatusRequest());
        addRenderableWidget(Button.builder(Component.literal("刷新 / Refresh"), b -> GpuNetwork.CHANNEL.sendToServer(new C2SGpuStatusRequest()))
                .bounds(this.width / 2 - 100, this.height - 40, 200, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        GpuStatusSnapshot s = GpuStatusHolder.latest();
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);

        int y = 46;
        if (s.engineReady()) {
            graphics.drawString(this.font, "设备 / Device: " + s.deviceName(), this.width / 2 - 150, y, 0xFFFFFF); y += 12;
            graphics.drawString(this.font, "厂商 / Vendor: " + s.vendor(), this.width / 2 - 150, y, 0xFFFFFF); y += 12;
            graphics.drawString(this.font, "版本 / Version: " + s.version(), this.width / 2 - 150, y, 0xFFFFFF); y += 12;
            graphics.drawString(this.font, "显存 / Memory: " + formatBytes(s.maxMemoryBytes()), this.width / 2 - 150, y, 0xFFFFFF); y += 12;
            graphics.drawString(this.font, "计算单元 / Compute units: " + s.computeUnits(), this.width / 2 - 150, y, 0xFFFFFF); y += 22;
            graphics.drawString(this.font, "已生成高度图 / Heightmaps: " + s.heightmapCalls(), this.width / 2 - 150, y, 0xFFFFFF); y += 12;
            graphics.drawString(this.font, "最近一次耗时 / Last: " + formatNanos(s.lastHeightmapNanos()), this.width / 2 - 150, y, 0xFFFFFF); y += 12;
            graphics.drawString(this.font, "总耗时 / Total: " + formatNanos(s.totalNanos()), this.width / 2 - 150, y, 0xFFFFFF); y += 12;
            if (s.heightmapCalls() > 0) {
                graphics.drawString(this.font, "平均耗时 / Average: " + formatNanos(s.totalNanos() / s.heightmapCalls()), this.width / 2 - 150, y, 0xFFFFFF);
            }
        } else {
            graphics.drawString(this.font, "GPU 不可用 / GPU unavailable", this.width / 2 - 150, y, 0xFFFFFF); y += 14;
            graphics.drawString(this.font, "原因 / Reason: " + s.deviceName(), this.width / 2 - 150, y, 0xFFFFFF);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "未知 / unknown";
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static String formatNanos(long nanos) {
        if (nanos <= 0) return "0 ms";
        if (nanos < 1_000_000) return String.format(Locale.ROOT, "%.2f ms", nanos / 1e6);
        if (nanos < 1_000_000_000) return String.format(Locale.ROOT, "%.1f ms", nanos / 1e6);
        return String.format(Locale.ROOT, "%.2f s", nanos / 1e9);
    }
}
