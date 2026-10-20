package dev.gpuaccel.gpu;

/** Lightweight description of an OpenCL device, used for the /gpuaccel status command. */
public record GpuDeviceInfo(String name, String vendor, String version, String type) {
    @Override
    public String toString() {
        return name + " (" + type + ", " + vendor + ", " + version + ")";
    }
}
