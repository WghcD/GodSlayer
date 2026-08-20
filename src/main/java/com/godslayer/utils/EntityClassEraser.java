package com.godslayer.utils;

import com.godslayer.GodSlayerNative;
import net.minecraft.world.entity.Entity;
import org.objectweb.asm.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 實體抹除實驗核心類 (修復版)
 * 修復點：ASM Frame擴展、Unsafe獲取回退機制、渲染字段名稱修正
 */
public class EntityClassEraser {

    private static final String LOG_PREFIX = "[EntityClassEraser] ";
    private static final boolean DEBUG = true;

    private static Instrumentation instrumentation;
    private static Object internalUnsafe; // 可能是 sun.misc.Unsafe 或 jdk.internal.misc.Unsafe
    private static MethodHandles.Lookup lookup;
    private static final AtomicBoolean loopHijacked = new AtomicBoolean(false);

    static {
        try {
            init();
        } catch (Throwable t) {
            // 即使初始化部分失敗也不要崩潰，讓程序跑起來
            log("初始化過程發生非致命錯誤: " + t.getMessage());
            t.printStackTrace();
        }
    }

    private static void init() throws Throwable {
        log("開始初始化底層環境...");

        // 1. 獲取 Instrumentation
        try {
            if (GodSlayerNative.InstIsReady) {
                instrumentation = GodSlayerNative.inst;
            } else {
                log("警告: NativeGuard.inst 不可用");
            }
            if (instrumentation != null) {
                log("技術點4/5: 成功獲取 Instrumentation 實例");
            }
        } catch (Exception e) {
            log("獲取 Instrumentation 失敗");
        }

        // 2. 獲取 IMPL_LOOKUP 和 Internal Unsafe (技術點 12)
        // 增加了黑魔法去除 final 修飾符的嘗試
        try {
            Field implLookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");

            // 黑魔法：嘗試去除 final 標誌，這在某些JVM版本下能繞過嚴格的模塊檢查
            Field modifiersField = Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(implLookupField, implLookupField.getModifiers() & ~Modifier.FINAL);

            implLookupField.setAccessible(true);
            lookup = (MethodHandles.Lookup) implLookupField.get(null);
            log("技術點12: 成功獲取 IMPL_LOOKUP (使用黑魔法突破)");

            // 嘗試獲取 jdk.internal.misc.Unsafe
            try {
                Class<?> internalUnsafeClass = Class.forName("jdk.internal.misc.Unsafe");
                MethodHandles.Lookup internalLookup = MethodHandles.privateLookupIn(internalUnsafeClass, lookup);
                var theUnsafeHandle = internalLookup.findStaticVarHandle(internalUnsafeClass, "theUnsafe", internalUnsafeClass);
                internalUnsafe = theUnsafeHandle.get();
                log("技術點12: 成功獲取 Internal Unsafe (jdk.internal.misc.Unsafe)");
            } catch (Exception e) {
                log("無法獲取 Internal Unsafe，嘗試標準 Unsafe...");
            }
        } catch (Throwable e) {
            log("無法通過 IMPL_LOOKUP 獲取 Internal Unsafe: " + e.getMessage());
        }

        // 3. 回退機制：如果 Internal Unsafe 獲取失敗，嘗試獲取標準的 sun.misc.Unsafe
        // 這保證了 internalUnsafe 變量不爲 null，避免後面 attemptKlassPtrSwap 報錯
        if (internalUnsafe == null) {
            try {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                Field theUnsafeField = unsafeClass.getDeclaredField("theUnsafe");
                theUnsafeField.setAccessible(true);
                internalUnsafe = theUnsafeField.get(null);
                log("技術點12(回退): 成功獲取標準 Unsafe (sun.misc.Unsafe)");
            } catch (Exception ex) {
                log("技術點12(回退): 獲取標準 Unsafe 也失敗！內存操作將不可用。");
                internalUnsafe = null; // 明確置空
            }
        }
    }

    public static void eraseEntity(Entity targetEntity) {
        if (targetEntity == null) {
            log("目標實體爲空");
            return;
        }

        String entityName = targetEntity.getClass().getName();
        log("========== 開始對實體 " + entityName + " 進行抹除操作 ==========");



        eraseFromRender(targetEntity);

        // Silence Kill 是最核心的邏輯，最後執行以確保覆蓋
        applySilenceKillAndIsolation(targetEntity.getClass());



        log("========== 抹除已結束 ==========");
    }

    /**
     * 技術點 13: Silence Kill (修復版)
     * 添加了 ClassReader.EXPAND_FRAMES 以解決 LocalVariablesSorter 報錯
     */
    private static void applySilenceKillAndIsolation(Class<?> targetClass) {
        log("技術點13(Silence Kill): 正在重定義類 " + targetClass.getName() + "...");
        try {
            byte[] originalBytes = getClassBytes(targetClass);

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                    // 跳過靜態初始化塊，防止類加載崩潰
                    if ("<clinit>".equals(name)) {
                        return mv;
                    }

                    return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
                        @Override
                        protected void onMethodEnter() {
                            if (DEBUG) log("技術點13: 拦截方法 " + name + descriptor);

                            Type returnType = Type.getReturnType(descriptor);
                            if (returnType.equals(Type.VOID_TYPE)) {
                                this.returnValue();
                            } else {
                                if (returnType.getSort() == Type.OBJECT || returnType.getSort() == Type.ARRAY) {
                                    this.visitInsn(Opcodes.ACONST_NULL);
                                } else if (returnType.getSort() >= Type.BOOLEAN && returnType.getSort() <= Type.INT) {
                                    this.visitInsn(Opcodes.ICONST_0);
                                } else if (returnType.getSort() == Type.LONG) {
                                    this.visitInsn(Opcodes.LCONST_0);
                                } else if (returnType.getSort() == Type.FLOAT) {
                                    this.visitInsn(Opcodes.FCONST_0);
                                } else if (returnType.getSort() == Type.DOUBLE) {
                                    this.visitInsn(Opcodes.DCONST_0);
                                }
                                this.returnValue();
                            }
                        }
                    };
                }
            };

            ClassReader cr = new ClassReader(originalBytes);
            // 關鍵修復：必須使用 EXPAND_FRAMES，否則 AdviceAdapter 會拋出 IllegalArgumentException
            cr.accept(cv, ClassReader.EXPAND_FRAMES | ClassReader.SKIP_DEBUG);

            byte[] transformedBytes = cw.toByteArray();

            if (instrumentation != null && instrumentation.isRedefineClassesSupported()) {
                instrumentation.redefineClasses(new ClassDefinition(targetClass, transformedBytes));
                log("技術點13: 類重定義成功。");
            } else {
                log("技術點13: Instrumentation 不支持重定義，跳過。");
            }

        } catch (Throwable e) {
            log("Silence Kill 執行失敗: " + e.getMessage());
            e.printStackTrace();
        }
    }




    /**
     * 技術點 6, 8, 9: 渲染覆蓋 (修復版)
     * 修正字段名稱爲 entityRenderDispatcher
     */
    private static void eraseFromRender(Entity target) {
        log("技術點6/8/9: 正在劫持渲染系統...");
        try {
            Object mcInst = net.minecraft.client.Minecraft.getInstance();
            if (mcInst == null) return;

            // 修復：1.20.1 中字段名爲 entityRenderDispatcher，而不是 entityRenderer
            Object renderer = mcInst.getClass().getDeclaredField("entityRenderDispatcher").get(mcInst);

            Class<?> erdClass = renderer.getClass();
            // 這個字段名通常在混淆後可能變化，但在 Mojang 映射下是 renderers
            // 如果這裏還報錯，說明你的環境是 Yarn 或其他映射，請對應修改
            Field mapField = erdClass.getDeclaredField("renderers");
            mapField.setAccessible(true);

            Map renderers = (Map) mapField.get(renderer);
            renderers.remove(target.getClass());
            log("技術點8: 已移除實體的渲染器映射。");

        } catch (NoSuchFieldException e) {
            log("技術點8: 找不到渲染器字段，請檢查映射 (entityRenderDispatcher/renderers)。錯誤: " + e.getMessage());
        } catch (Exception e) {
            log("技術點6/8/9: 渲染劫持失敗: " + e.getMessage());
        }
    }




    private static byte[] getClassBytes(Class clazz) throws Exception {
        String className = clazz.getName().replace('.', '/') + ".class";
        InputStream is = clazz.getClassLoader().getResourceAsStream(className);
        if (is == null) {
            is = ClassLoader.getSystemResourceAsStream(className);
        }
        byte[] bytes = is.readAllBytes();
        is.close();
        return bytes;
    }

    private static void log(String msg) {
        if (DEBUG) {
            System.out.println(LOG_PREFIX + msg);
        }
    }
}
