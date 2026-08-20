//超早期加载
package com.godslayer.core;

import net.minecraftforge.fml.loading.ImmediateWindowProvider;

import java.util.Optional;
import java.util.function.*;

public final class MyIWP implements ImmediateWindowProvider
{

    static{
        System.out.println("\n\n\n\n\n\n\nGodSlayer IWP Early Loader Running.\n\n\n\n\n\n");
    }

    @Override
    public String name() {
        return "AAA";
    }

    @Override
    public Runnable initialize(String[] arguments) {

        return () -> {

            // 这里可以做窗口轮询等操作，注意不要阻塞
            // 每帧调用一次，建议只做轻量操作
        };
    }

    @Override
    public void updateFramebufferSize(IntConsumer width, IntConsumer height) {

    }

    @Override
    public long setupMinecraftWindow(IntSupplier width, IntSupplier height, Supplier<String> title, LongSupplier monitor) {
        return 0;
    }

    @Override
    public boolean positionWindow(Optional<Object> monitor, IntConsumer widthSetter, IntConsumer heightSetter, IntConsumer xSetter, IntConsumer ySetter) {
        return false;
    }

    @Override
    public <T> Supplier<T> loadingOverlay(Supplier<?> mc, Supplier<?> ri, Consumer<Optional<Throwable>> ex, boolean fade) {
        return null;
    }

    @Override
    public void updateModuleReads(ModuleLayer layer) {

    }

    @Override
    public void periodicTick() {

    }

    @Override
    public String getGLVersion() {
        return "";
    }
}