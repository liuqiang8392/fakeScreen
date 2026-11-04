package top.chuyb.fakeScreen;

import android.content.ContentResolver;
import android.provider.Settings;
import android.util.Log;
import io.github.libxposed.api.XposedModule;

/**
 * 控制系统“极暗”层（Reduce Bright Colors / MIUI 极暗模式）。
 *
 * <p>背光亮度存在硬件下限（本机型 {@code mScreenBrightnessMinimum = 0.0026868589}），
 * 压到这个值之后就无法再低了。系统另有一层与背光无关的软件压暗：它通过
 * {@code Settings.Secure} 中的 {@code reduce_bright_colors_activated} 与
 * {@code reduce_bright_colors_level} 开关，SurfaceFlinger 会给整个显示层套一个
 * 颜色矩阵（实测 level=50 时系数 0.522、level=90 时 0.140、level=100 时 0.044），
 * 从而在背光已经最低时继续把画面压暗。</p>
 *
 * <p>这两个键属于 {@link Settings.Secure}，普通应用没有写权限，因此由界面应用通过广播
 * 请求 system_server 侧的 {@link FakeScreenIpc} 写入；写入后系统的
 * AccessibilityManagerService 会立即重算颜色矩阵，无需重启。</p>
 *
 * @since 2.2
 * @author chuyb-re
 */
final class DimController {

    /** 极暗开关所在的 Settings.Secure 键。 */
    private static final String KEY_RBC_ACTIVATED = "reduce_bright_colors_activated";

    /** 极暗强度所在的 Settings.Secure 键，取值 0~100。 */
    private static final String KEY_RBC_LEVEL = "reduce_bright_colors_level";

    private DimController() {
        // 工具类，禁止实例化。
    }

    /**
     * 应用极暗强度：0 表示关闭极暗，1~100 表示开启并设置强度。
     *
     * @param module 用于框架日志的模块实例。
     * @param level  极暗强度，0~100。
     */
    static void apply(XposedModule module, int level) {
        ContentResolver cr = requireContentResolver(module);
        if (cr == null) return;

        int clamped = FakeScreenState.clampDimLevel(level);
        try {
            if (clamped <= 0) {
                Settings.Secure.putInt(cr, KEY_RBC_ACTIVATED, 0);
                XLog.log(Log.INFO, "ReduceBrightColors disabled");
                return;
            }
            Settings.Secure.putInt(cr, KEY_RBC_LEVEL, clamped);
            Settings.Secure.putInt(cr, KEY_RBC_ACTIVATED, 1);
            XLog.log(Log.INFO, "ReduceBrightColors enabled, level=" + clamped);
        } catch (Exception e) {
            XLog.error("Failed to apply reduce bright colors level", e);
        }
    }

    /**
     * 把系统当前的极暗状态同步到 Settings.Global 镜像，供界面进程展示。
     *
     * <p>Android 12 起 {@code @hide} 的 Settings.Secure 键只允许 system_server / 系统应用读取，
     * 界面进程读会抛 {@link SecurityException}，因此开机时由这里把真实状态写进镜像键。</p>
     *
     * @param module 用于框架日志的模块实例。
     */
    static void syncFromSystem(XposedModule module) {
        ContentResolver cr = requireContentResolver(module);
        if (cr == null) return;

        try {
            int activated = Settings.Secure.getInt(cr, KEY_RBC_ACTIVATED, 0);
            int level = activated == 1
                    ? FakeScreenState.clampDimLevel(
                            Settings.Secure.getInt(cr, KEY_RBC_LEVEL, FakeScreenState.DEFAULT_DIM_LEVEL))
                    : 0;
            Settings.Global.putInt(cr, FakeScreenState.KEY_FAKE_SCREEN_DIM_LEVEL, level);
            XLog.log(Log.INFO, "Dim state synced from system, level=" + level);
        } catch (Exception e) {
            XLog.error("Failed to sync reduce bright colors state", e);
        }
    }

    /**
     * @param module 用于框架日志的模块实例。
     * @return system_server 中捕获的系统 ContentResolver，尚未就绪时返回 null。
     */
    private static ContentResolver requireContentResolver(XposedModule module) {
        android.content.Context context = FakeScreenIpc.getSystemContext();
        if (context == null) {
            XLog.log(Log.ERROR, "System context is not available, skip reduce bright colors change");
            return null;
        }
        return context.getContentResolver();
    }
}
