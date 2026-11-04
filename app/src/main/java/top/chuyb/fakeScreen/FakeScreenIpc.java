package top.chuyb.fakeScreen;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.ContextCompat;

import io.github.libxposed.api.XposedModule;

/**
 * 界面应用与 system_server 之间的 IPC 与状态同步：从 PhoneWindowManager 获取系统 Context、
 * 注册开关广播接收器、向 hook 层暴露当前开关状态，并在每次切换时应用功能副作用（屏幕超时）。
 * 本类不安装任何 hook。
 *
 * @since 1.0
 * @author chuyb-re
 */
final class FakeScreenIpc {


    private static Context sSystemContext = null;
    private static volatile boolean sIsFakeScreenEnabled = false;
    private static volatile int sConfiguredBrightness = FakeScreenState.DEFAULT_BRIGHTNESS;

    private FakeScreenIpc() {
        // 工具类，禁止实例化。
    }

    /**
     * 当前开关状态，由开关广播保持同步。
     *
     * @return 已开启时返回 true。
     */
    static boolean isEnabled() {
        return sIsFakeScreenEnabled;
    }

    /**
     * 按下电源键时要降到的目标亮度，由亮度广播保持同步。
     *
     * @return 0 表示熄屏，1~255 表示目标亮度。
     */
    static int getConfiguredBrightness() {
        return sConfiguredBrightness;
    }

    /**
     * 从 PhoneWindowManager 捕获的系统 Context。
     *
     * @return 捕获到的 Context；{@link #attach} 尚未执行时返回 null。
     */
    static Context getSystemContext() {
        return sSystemContext;
    }

    /**
     * 从 PhoneWindowManager 实例获取系统 Context，并在该实例存在时注册开关广播接收器。
     *
     * @param module      用于框架日志的模块实例。
     * @param pwmClass    PhoneWindowManager 的 Class 引用。
     * @param pwmInstance PhoneWindowManager 实例对象。
     */
    static void attach(XposedModule module, Class<?> pwmClass, Object pwmInstance) {
        sSystemContext = ReflectUtils.getSystemContext(pwmClass, pwmInstance);
        if (pwmInstance == null) return;
        registerToggleReceiver(module);
    }

    /**
     * 读取上次保存的开关与亮度设定并注册广播接收器，使运行时状态、Settings.Global 与界面三者保持同步。
     *
     * <p>开关状态跨重启延续：开机时沿用上次的 {@link FakeScreenState#KEY_FAKE_SCREEN_ENABLED}，
     * 不再强制复位为关闭；因此若开机后仍是开启状态，需要立即重新套用屏幕超时覆盖。</p>
     *
     * @param module 用于框架日志的模块实例。
     */
    private static void registerToggleReceiver(XposedModule module) {
        try {
            if (sSystemContext != null) {
                ContentResolver cr = sSystemContext.getContentResolver();
                // 开机时沿用上次的开关状态，使功能跨重启延续
                sIsFakeScreenEnabled = Settings.Global.getInt(
                        cr, FakeScreenState.KEY_FAKE_SCREEN_ENABLED, 0) == 1;
                sConfiguredBrightness = FakeScreenState.clampBrightness(Settings.Global.getInt(
                        cr, FakeScreenState.KEY_FAKE_SCREEN_BRIGHTNESS, FakeScreenState.DEFAULT_BRIGHTNESS));

                // 开关沿用上次状态，这里按当前状态重新应用屏幕超时覆盖
                applyScreenTimeout(module, sIsFakeScreenEnabled);

                // 降亮期间若重启过，屏幕上还留着降亮值，这里按上次保存的值还原
                BrightnessController.restoreLeftover(module);

                // 把系统真实的极暗状态同步到 Settings.Global 镜像，供界面进程展示
                DimController.syncFromSystem(module);

                IntentFilter filter = new IntentFilter();
                filter.addAction(FakeScreenState.ACTION_TOGGLE_FAKE_SCREEN);
                filter.addAction(FakeScreenState.ACTION_SET_BRIGHTNESS);
                filter.addAction(FakeScreenState.ACTION_SET_DIM_LEVEL);
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (FakeScreenState.ACTION_SET_BRIGHTNESS.equals(intent.getAction())) {
                            handleBrightnessChange(module, context, intent);
                        } else if (FakeScreenState.ACTION_SET_DIM_LEVEL.equals(intent.getAction())) {
                            handleDimLevelChange(module, context, intent);
                        } else {
                            handleToggle(module, context, intent);
                        }
                    }
                };

                ContextCompat.registerReceiver(
                        sSystemContext,
                        receiver,
                        filter,
                        ContextCompat.RECEIVER_EXPORTED
                );
                XLog.log(Log.INFO,"Register BroadcastReceiver successful, enabled="
                        + sIsFakeScreenEnabled + ", brightness=" + sConfiguredBrightness);
            } else {
                XLog.log(Log.ERROR,"Failed to extract Context from PhoneWindowManager");
            }
        } catch (Exception e) {
            XLog.error("Failed to register BroadcastReceiver", e);
        }
    }

    /**
     * 处理界面开关广播：同步运行时开关状态、Settings.Global 与屏幕超时。
     *
     * @param module  用于框架日志的模块实例。
     * @param context 广播携带的系统 Context。
     * @param intent  开关广播。
     */
    private static void handleToggle(XposedModule module, Context context, Intent intent) {
        boolean enabled = intent.getBooleanExtra(FakeScreenState.EXTRA_ENABLED, false);
        sIsFakeScreenEnabled = enabled;

        try {
            Settings.Global.putInt(
                    context.getContentResolver(),
                    FakeScreenState.KEY_FAKE_SCREEN_ENABLED,
                    enabled ? 1 : 0
            );
        } catch (Exception e) {
            XLog.error("Failed to write Settings.Global in system_server", e);
        }

        if (!enabled) {
            // 关闭功能时把可能残留的降亮状态还原，避免屏幕一直停留在低亮度。
            BrightnessController.restoreIfDimmed(module);
        }
        applyScreenTimeout(module, enabled);
    }

    /**
     * 处理界面亮度广播：保存目标亮度，并在已降亮时立即应用到当前屏幕。
     *
     * @param module  用于框架日志的模块实例。
     * @param context 广播携带的系统 Context。
     * @param intent  亮度广播。
     */
    private static void handleBrightnessChange(XposedModule module, Context context, Intent intent) {
        int brightness = FakeScreenState.clampBrightness(intent.getIntExtra(
                FakeScreenState.EXTRA_BRIGHTNESS, FakeScreenState.DEFAULT_BRIGHTNESS));
        sConfiguredBrightness = brightness;

        try {
            Settings.Global.putInt(
                    context.getContentResolver(),
                    FakeScreenState.KEY_FAKE_SCREEN_BRIGHTNESS,
                    brightness
            );
        } catch (Exception e) {
            XLog.error("Failed to write Settings.Global brightness in system_server", e);
        }
        BrightnessController.updateIfDimmed(module, brightness);
    }

    /**
     * 处理界面极暗广播：保存强度并立即写入系统设置，使屏幕当场变暗或恢复。
     *
     * @param module  用于框架日志的模块实例。
     * @param context 广播携带的系统 Context。
     * @param intent  极暗广播。
     */
    private static void handleDimLevelChange(XposedModule module, Context context, Intent intent) {
        int level = FakeScreenState.clampDimLevel(intent.getIntExtra(
                FakeScreenState.EXTRA_DIM_LEVEL, FakeScreenState.DEFAULT_DIM_LEVEL));

        try {
            Settings.Global.putInt(
                    context.getContentResolver(),
                    FakeScreenState.KEY_FAKE_SCREEN_DIM_LEVEL,
                    level
            );
        } catch (Exception e) {
            XLog.error("Failed to write Settings.Global dim level in system_server", e);
        }
        DimController.apply(module, level);
    }

    /**
     * 应用功能对应的屏幕超时副作用：功能开启时让屏幕永不自动熄灭，
     * 功能关闭时还原用户原本设置的超时。
     *
     * @param module  用于框架日志的模块实例。
     * @param enabled 当前开关状态。
     */
    private static void applyScreenTimeout(XposedModule module, boolean enabled) {
        try {
            if (enabled) {
                ScreenTimeoutController.saveOldTimeOut();
            } else {
                ScreenTimeoutController.recoveryTimeOut();
            }
        } catch (Exception e) {
            XLog.error("Failed to apply screen off timeout", e);
        }
    }
}
