package top.chuyb.fakeScreen;

import android.util.Log;

/**
 * 日志工具。
 *
 * <p>本类位于界面进程与 system_server 进程共用的代码里，因此不能直接依赖
 * {@code io.github.libxposed.api} 的类型：普通应用进程里没有 libxposed api，
 * 一旦类加载到带该类型字段的类就会抛 {@code NoClassDefFoundError}。
 * 这里把模块实例保存为 {@link Object}，转发到框架日志时用反射调用，
 * 同时始终输出到 logcat。</p>
 *
 * @author chuyb-re
 * @since 2.1
 */
public final class XLog {

    private static final String TAG = "FakeScreen";

    /** 模块实例，界面进程里为 null。 */
    private static volatile Object sModule;

    private XLog() {
        // 工具类，禁止实例化。
    }

    /**
     * 注入 Xposed 模块实例，用于把日志同时写入框架日志。
     *
     * @param module 模块实例（{@code XposedModule}）。
     */
    public static void init(Object module) {
        sModule = module;
    }

    /**
     * 输出一条普通日志。
     *
     * @param level   logcat 级别，如 {@link Log#INFO}。
     * @param message 日志内容。
     */
    public static void log(int level, String message) {
        Log.println(level, TAG, message);
        mirror(level, message, null);
    }

    /**
     * 输出一条错误日志。
     *
     * @param message 日志内容。
     * @param e       异常对象。
     */
    public static void error(String message, Throwable e) {
        Log.e(TAG, message, e);
        mirror(Log.ERROR, message, e);
    }

    /**
     * 把日志转发到框架日志；模块实例不存在或反射失败时静默忽略。
     *
     * @param level   logcat 级别。
     * @param message 日志内容。
     * @param e       异常对象，可为 null。
     */
    private static void mirror(int level, String message, Throwable e) {
        Object module = sModule;
        if (module == null) return;
        try {
            if (e == null) {
                module.getClass()
                        .getMethod("log", int.class, String.class, String.class)
                        .invoke(module, level, TAG, message);
            } else {
                module.getClass()
                        .getMethod("log", int.class, String.class, String.class, Throwable.class)
                        .invoke(module, level, TAG, message, e);
            }
        } catch (Exception ignored) {
            // 框架日志不可用时保留 logcat 输出即可。
        }
    }
}
