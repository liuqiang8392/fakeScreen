package top.chuyb.fakeScreen;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.WindowCompat;

/**
 * 主界面（表现层）：只负责加载 XML 布局、设置窗口外观并分发用户交互事件，
 * 状态读写与进程间通信全部由业务层 {@link FakeScreenState} 承担。
 *
 * @since 1.0
 * @author chuyb-re
 */
public class MainActivity extends AppCompatActivity {

    /** 亮度滑块，负责粗调。 */
    private SeekBar brightnessSeekBar;

    /** 亮度数值文本，展示当前目标亮度。 */
    private TextView brightnessValueText;

    /** 亮度数字输入框，用于直接输入精确值。 */
    private EditText brightnessInput;

    /** 极暗强度滑块，负责在背光最低之后继续压暗画面。 */
    private SeekBar dimSeekBar;

    /** 极暗强度数值文本。 */
    private TextView dimValueText;

    /**
     * 初始化界面布局、窗口元素与各控件的事件监听。
     *
     * @param savedInstanceState 保存的界面状态，可能为 null。
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupEdgeToEdgeWindow();
        setContentView(R.layout.activity_main);
        initSwitchButton();
        initBrightnessSlider();
        initDimSlider();
        initHideIconButton();
        initGithubButton();
        initTipsButton();
    }

    /**
     * 开启 edge-to-edge 绘制，使 XML 布局延伸到系统栏下方。
     *
     * <p>系统栏颜色、状态栏图标明暗与窗口底色都在 {@code themes.xml} 中声明；
     * 只有这一个窗口开关没有对应的主题属性，因此保留在代码中。</p>
     */
    private void setupEdgeToEdgeWindow() {
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
    }

    /**
     * 绑定功能开关控件，读取全局设置状态，并在切换时发送广播。
     */
    private void initSwitchButton() {
        SwitchCompat switchBtn = findViewById(R.id.switch_fake_screen);
        if (switchBtn == null) return;
        switchBtn.setChecked(FakeScreenState.isEnabled(this));
        switchBtn.setOnCheckedChangeListener((buttonView, isChecked) ->
                FakeScreenState.setEnabled(this, isChecked));
    }

    /**
     * 绑定“电源键亮度”滑块，读取已保存的目标亮度，并在拖动结束时发送广播。
     *
     * <p>滑块取值 0~255 与系统亮度量程一致：0 表示按下电源键熄灭屏幕，
     * 大于 0 表示按下电源键把亮度降到该值。</p>
     */
    private void initBrightnessSlider() {
        brightnessSeekBar = findViewById(R.id.seek_bar_brightness);
        brightnessValueText = findViewById(R.id.tv_brightness_value);
        brightnessInput = findViewById(R.id.et_brightness);
        View minusButton = findViewById(R.id.btn_brightness_minus);
        View plusButton = findViewById(R.id.btn_brightness_plus);
        if (brightnessSeekBar == null || brightnessValueText == null) return;

        brightnessSeekBar.setMax(FakeScreenState.MAX_BRIGHTNESS);
        int current = FakeScreenState.getBrightness(this);
        applyBrightness(current, false);

        brightnessSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                // 拖动过程中只更新展示，不写设置，避免频繁发送广播。
                brightnessValueText.setText(formatBrightness(progress));
                if (fromUser && brightnessInput != null && !brightnessInput.hasFocus()) {
                    brightnessInput.setText(String.valueOf(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                // 无需额外处理。
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                applyBrightness(bar.getProgress(), true);
            }
        });

        if (minusButton != null) {
            minusButton.setOnClickListener(v -> stepBrightness(-1));
        }
        if (plusButton != null) {
            plusButton.setOnClickListener(v -> stepBrightness(1));
        }

        if (brightnessInput != null) {
            // 输入框只在编辑结束时提交，避免逐字符触发广播。
            brightnessInput.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    commitBrightnessInput();
                    brightnessInput.clearFocus();
                    hideKeyboard();
                    return true;
                }
                return false;
            });
            brightnessInput.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    // 聚焦后全选原有数字，便于直接覆盖输入；post 一下以避开点击带来的光标定位。
                    brightnessInput.post(brightnessInput::selectAll);
                } else {
                    commitBrightnessInput();
                }
            });
        }
    }

    /**
     * 绑定“极暗强度”滑块，读取系统当前真实生效的极暗状态，并在拖动结束时发送广播。
     *
     * <p>该滑块控制的正是系统设置里的极暗开关（Reduce Bright Colors）：
     * 0 表示关闭，1~100 表示开启并设置压暗强度。因为写的是 Settings.Secure，
     * 普通应用无权限，所以这里只发广播，由 system_server 侧写入。</p>
     */
    private void initDimSlider() {
        dimSeekBar = findViewById(R.id.seek_bar_dim);
        dimValueText = findViewById(R.id.tv_dim_value);
        if (dimSeekBar == null || dimValueText == null) return;

        dimSeekBar.setMax(FakeScreenState.MAX_DIM_LEVEL);
        // 读取 system_server 同步过来的镜像值：界面进程无权直接读 Settings.Secure 的极暗键。
        int current = FakeScreenState.getDimLevel(this);
        applyDimLevel(current, false);

        dimSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                // 拖动过程中只刷新展示，松手才真正写入系统设置。
                if (dimValueText != null) {
                    dimValueText.setText(formatDimLevel(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                // 无需额外处理。
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                applyDimLevel(bar.getProgress(), true);
            }
        });
    }

    /**
     * 统一更新极暗滑块与数值文本，并按需保存。
     *
     * @param value   极暗强度，超出 0–100 的部分会被夹取。
     * @param persist 是否发送广播保存。
     */
    private void applyDimLevel(int value, boolean persist) {
        int clamped = FakeScreenState.clampDimLevel(value);
        if (dimSeekBar != null) {
            dimSeekBar.setProgress(clamped);
        }
        if (dimValueText != null) {
            dimValueText.setText(formatDimLevel(clamped));
        }
        if (persist) {
            FakeScreenState.setDimLevel(this, clamped);
        }
    }

    /**
     * 把极暗强度格式化为界面展示文本。
     *
     * @param level 极暗强度，0 表示关闭。
     * @return 展示文本。
     */
    private String formatDimLevel(int level) {
        if (level <= 0) {
            return getString(R.string.dim_value_off);
        }
        return getString(R.string.dim_value, level);
    }

    /**
     * 按 ±1 调整目标亮度，并立即保存。
     *
     * @param delta 调整量，-1 表示减一档，+1 表示加一档。
     */
    private void stepBrightness(int delta) {
        // 若用户正在输入框里手输数值，先提交再按 ± 调整，避免两步结果互相覆盖。
        if (brightnessInput != null && brightnessInput.hasFocus()) {
            commitBrightnessInput();
            brightnessInput.clearFocus();
            hideKeyboard();
        }
        int base = brightnessSeekBar == null
                ? FakeScreenState.getBrightness(this) : brightnessSeekBar.getProgress();
        applyBrightness(base + delta, true);
    }

    /**
     * 把输入框中的数字提交为目标亮度。
     *
     * <p>输入为空或非法时回退到当前滑块值，不会产生负值或超出 255。</p>
     */
    private void commitBrightnessInput() {
        if (brightnessInput == null) return;
        int fallback = brightnessSeekBar == null
                ? FakeScreenState.getBrightness(this) : brightnessSeekBar.getProgress();
        int value = parseBrightness(brightnessInput.getText().toString(), fallback);
        applyBrightness(value, true);
        // applyBrightness 在输入框聚焦时不会改写文本，这里统一归一化显示。
        brightnessInput.setText(String.valueOf(FakeScreenState.clampBrightness(value)));
    }

    /**
     * 把字符串解析为亮度值。
     *
     * @param raw      输入框文本。
     * @param fallback 解析失败时使用的兜底值。
     * @return 解析出的亮度值，可能为负，由调用方统一夹取。
     */
    private int parseBrightness(String raw, int fallback) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return fallback;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 统一更新滑块、数值文本与输入框，并按需保存。
     *
     * @param value   目标亮度，超出 0–255 的部分会被夹取。
     * @param persist 是否发送广播保存。
     */
    private void applyBrightness(int value, boolean persist) {
        int clamped = FakeScreenState.clampBrightness(value);
        if (brightnessSeekBar != null) {
            brightnessSeekBar.setProgress(clamped);
        }
        if (brightnessValueText != null) {
            brightnessValueText.setText(formatBrightness(clamped));
        }
        if (brightnessInput != null && !brightnessInput.hasFocus()) {
            brightnessInput.setText(String.valueOf(clamped));
        }
        if (persist) {
            FakeScreenState.setBrightness(this, clamped);
        }
    }

    /**
     * 收起输入法。
     */
    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && brightnessInput != null) {
            imm.hideSoftInputFromWindow(brightnessInput.getWindowToken(), 0);
        }
    }

    /**
     * 把目标亮度格式化为界面展示文本。
     *
     * @param brightness 目标亮度，0 表示熄屏。
     * @return 展示文本。
     */
    private String formatBrightness(int brightness) {
        if (brightness <= 0) {
            return getString(R.string.brightness_value_off);
        }
        return getString(R.string.brightness_value, brightness);
    }

    /**
     * 绑定“隐藏桌面图标”开关，读取并应用启动器组件的启用状态。
     */
    private void initHideIconButton() {
        SwitchCompat switchBtn = findViewById(R.id.switch_hide_icon);
        if (switchBtn == null) return;
        switchBtn.setChecked(DesktopIconHelper.isHidden(this));
        switchBtn.setOnCheckedChangeListener((buttonView, isChecked) ->
                DesktopIconHelper.applySetting(this, isChecked));
    }

    /**
     * 绑定 GitHub 按钮点击事件，在浏览器中打开仓库地址。
     */
    private void initGithubButton() {
        LinearLayout btnGithub = findViewById(R.id.btn_github);
        if (btnGithub == null) return;
        btnGithub.setOnClickListener(v -> {
            try {
                String url = getString(R.string.github_repo_url);
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, R.string.toast_browser_unavailable, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 绑定使用提示按钮点击事件，弹出说明对话框。
     */
    private void initTipsButton() {
        LinearLayout btnTips = findViewById(R.id.btn_tips);
        if (btnTips == null) return;

        btnTips.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_tips_title)
                    .setMessage(R.string.tip)
                    .setPositiveButton(R.string.dialog_positive_btn, null)
                    .show();
        });
    }
}
