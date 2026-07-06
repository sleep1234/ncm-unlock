package com.raincat.dolby_beta.hook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import com.raincat.dolby_beta.helper.SettingsHelper;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClassIfExists;

/**
 * NCM-Unlock: 设置页面 Hook
 * 在网易云音乐设置中添加模块设置入口
 *
 * 新版网易云音乐(v9.5.30+)的设置页面是基于React Native的MainProcessRNActivity，
 * 需要Hook该Activity的onResume来注入设置入口。
 */
public class SettingHook {
    private final Context context;
    private static final String SETTINGS_ROW_TAG = "ncm_unlock_settings_row";
    private TextView titleView, subView;

    private static final int MAX_RETRIES = 5;
    private static final int RETRY_INTERVAL_MS = 300;

    public SettingHook(Context context, int versionCode) {
        this.context = context;

        // Hook RN-based settings page (MainProcessRNActivity)
        String rnActivityName = "com.netease.cloudmusic.music.biz.rn.activity.MainProcessRNActivity";
        Class<?> rnActivityClass = findClassIfExists(rnActivityName, context.getClassLoader());
        if (rnActivityClass != null) {
            XposedBridge.log("[ncm-unlock] SettingHook: hooking RN activity: " + rnActivityName);
            hookActivity(rnActivityClass);
        } else {
            XposedBridge.log("[ncm-unlock] SettingHook: RN activity not found: " + rnActivityName);
        }

        // Hook legacy native SettingActivity
        String settingActivityName;
        if (versionCode >= 8007000) {
            settingActivityName = "com.netease.cloudmusic.music.biz.setting.activity.SettingActivity";
        } else {
            settingActivityName = "com.netease.cloudmusic.activity.SettingActivity";
        }
        Class<?> settingActivityClass = findClassIfExists(settingActivityName, context.getClassLoader());
        if (settingActivityClass != null) {
            XposedBridge.log("[ncm-unlock] SettingHook: hooking native activity: " + settingActivityName);
            hookActivity(settingActivityClass);
        } else {
            XposedBridge.log("[ncm-unlock] SettingHook: native SettingActivity not found: " + settingActivityName);
        }

        XposedBridge.log("[ncm-unlock] SettingHook initialized");
    }

    private void hookActivity(Class<?> activityClass) {
        findAndHookMethod(activityClass, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Activity activity = (Activity) param.thisObject;
                ensureSettingsRow(activity);
            }
        });
    }

    private void ensureSettingsRow(Activity activity) {
        try {
            // Check if our row already exists
            View contentView = activity.findViewById(android.R.id.content);
            if (contentView != null) {
                View existingRow = contentView.findViewWithTag(SETTINGS_ROW_TAG);
                if (existingRow != null) {
                    XposedBridge.log("[ncm-unlock] SettingHook: row already present");
                    return;
                }
            }

            // Try to insert into RN settings page
            if (tryInsertIntoRNSettings(activity)) {
                return;
            }

            // Fallback: try native settings page
            if (tryInsertViaContentRoot(activity)) {
                return;
            }

            // Schedule retry for async RN rendering
            XposedBridge.log("[ncm-unlock] SettingHook: scheduling retry");
            scheduleRetry(activity, 0);
        } catch (Exception e) {
            XposedBridge.log("[ncm-unlock] SettingHook: ensureSettingsRow failed: " + e.getMessage());
        }
    }

    private void scheduleRetry(Activity activity, int retryCount) {
        if (retryCount >= MAX_RETRIES) {
            XposedBridge.log("[ncm-unlock] SettingHook: max retries reached");
            return;
        }
        activity.getWindow().getDecorView().postDelayed(() -> {
            try {
                View cv = activity.findViewById(android.R.id.content);
                if (cv != null && cv.findViewWithTag(SETTINGS_ROW_TAG) != null) {
                    XposedBridge.log("[ncm-unlock] SettingHook: retry #" + retryCount + " found row already present");
                    return;
                }
                if (tryInsertIntoRNSettings(activity)) {
                    XposedBridge.log("[ncm-unlock] SettingHook: retry #" + retryCount + " succeeded (RN)");
                    return;
                }
                if (tryInsertViaContentRoot(activity)) {
                    XposedBridge.log("[ncm-unlock] SettingHook: retry #" + retryCount + " succeeded (native)");
                    return;
                }
                // Dump view tree on first retry for diagnosis
                if (retryCount == 0) {
                    dumpViewTree(activity);
                }
                scheduleRetry(activity, retryCount + 1);
            } catch (Exception e) {
                XposedBridge.log("[ncm-unlock] SettingHook: retry #" + retryCount + " exception: " + e.getMessage());
            }
        }, RETRY_INTERVAL_MS);
    }

    /**
     * Strategy for RN settings page:
     * Find the container that holds settings rows and insert our row.
     */
    private boolean tryInsertIntoRNSettings(Activity activity) {
        try {
            View contentView = activity.findViewById(android.R.id.content);
            if (contentView == null) return false;

            // Verify this is the Settings page
            if (!findTextInView(contentView, "设置")) {
                return false;
            }

            // Find the settings row list container
            ViewGroup rowListContainer = findRNSettingsContainer(contentView);
            if (rowListContainer == null) {
                XposedBridge.log("[ncm-unlock] SettingHook: RN: no settings container found");
                return false;
            }

            XposedBridge.log("[ncm-unlock] SettingHook: RN: found container: " + rowListContainer.getClass().getName() + ", children=" + rowListContainer.getChildCount());

            // Find an existing row to copy style from
            View styleRow = findRNSettingsRow(rowListContainer);
            LinearLayout ourRow = createSettingsRow(activity, styleRow);

            // Insert at position 0
            rowListContainer.addView(ourRow, 0);
            forceLayoutInRNContainer(ourRow, rowListContainer);
            XposedBridge.log("[ncm-unlock] SettingHook: RN: settings row inserted");
            return true;
        } catch (Exception e) {
            XposedBridge.log("[ncm-unlock] SettingHook: RN insert failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Strategy for native settings page:
     * Find a vertical container and insert our row.
     */
    private boolean tryInsertViaContentRoot(Activity activity) {
        try {
            View contentView = activity.findViewById(android.R.id.content);
            if (contentView == null) return false;

            ViewGroup targetContainer = findVerticalContainer(contentView);
            if (targetContainer == null) return false;

            View existingRow = findFirstSettingsRow(targetContainer);
            LinearLayout linearLayout = createSettingsRow(activity, existingRow);
            targetContainer.addView(linearLayout, 0);
            XposedBridge.log("[ncm-unlock] SettingHook: native: settings row inserted");
            return true;
        } catch (Exception e) {
            XposedBridge.log("[ncm-unlock] SettingHook: native insert failed: " + e.getMessage());
            return false;
        }
    }

    private boolean findTextInView(View root, String target) {
        return findTextInViewInternal(root, target, 0, 15);
    }

    private boolean findTextInViewInternal(View root, String target, int depth, int maxDepth) {
        if (depth > maxDepth) return false;
        if (root instanceof TextView) {
            CharSequence text = ((TextView) root).getText();
            if (text != null && text.toString().equals(target)) return true;
        }
        if (root instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
                if (findTextInViewInternal(((ViewGroup) root).getChildAt(i), target, depth + 1, maxDepth)) return true;
            }
        }
        return false;
    }

    /**
     * Find the scrollable container in the RN settings page.
     * Looks for a ViewGroup that contains >= 5 child ViewGroups that look like settings rows.
     */
    private ViewGroup findRNSettingsContainer(View root) {
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;

        int rowCount = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup && looksLikeSettingsRow((ViewGroup) child)) {
                rowCount++;
            }
        }
        if (rowCount >= 5) {
            XposedBridge.log("[ncm-unlock] SettingHook: RN: found container with " + rowCount + " rows: " + group.getClass().getName());
            return group;
        }

        for (int i = 0; i < group.getChildCount(); i++) {
            ViewGroup result = findRNSettingsContainer(group.getChildAt(i));
            if (result != null) return result;
        }

        return null;
    }

    private boolean looksLikeSettingsRow(ViewGroup group) {
        if (group.getChildCount() > 6) return false;
        return hasClickableWithText(group);
    }

    private View findRNSettingsRow(ViewGroup container) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof ViewGroup && hasClickableWithText((ViewGroup) child)) {
                return child;
            }
        }
        return null;
    }

    private boolean hasClickableWithText(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.isClickable() && child instanceof ViewGroup) {
                if (hasTextView((ViewGroup) child)) return true;
            }
        }
        return false;
    }

    private boolean hasTextView(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView && !((TextView) child).getText().toString().isEmpty()) {
                return true;
            }
            if (child instanceof ViewGroup && hasTextView((ViewGroup) child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Force measure and layout on a View inserted into a ReactViewGroup.
     */
    private void forceLayoutInRNContainer(View ourRow, ViewGroup container) {
        ourRow.post(() -> {
            try {
                int containerWidth = container.getWidth();
                if (containerWidth <= 0) containerWidth = container.getMeasuredWidth();
                if (containerWidth <= 0) containerWidth = 1080;

                ourRow.measure(
                    View.MeasureSpec.makeMeasureSpec(containerWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                );
                int rowHeight = ourRow.getMeasuredHeight();

                if (rowHeight <= 0) return;

                ourRow.layout(0, 0, containerWidth, rowHeight);

                // Shift existing rows down
                for (int i = 0; i < container.getChildCount(); i++) {
                    View child = container.getChildAt(i);
                    if (child != ourRow) {
                        child.offsetTopAndBottom(rowHeight);
                    }
                }

                // Re-apply shift after RN layout passes
                container.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                    try {
                        int currentTop = ourRow.getTop();
                        if (currentTop != 0) {
                            ourRow.offsetTopAndBottom(-currentTop);
                        }
                        for (int i = 0; i < container.getChildCount(); i++) {
                            View child = container.getChildAt(i);
                            if (child != ourRow && child.getTop() < rowHeight) {
                                child.offsetTopAndBottom(rowHeight - child.getTop());
                            }
                        }
                    } catch (Exception ignored) {}
                });

            } catch (Exception e) {
                XposedBridge.log("[ncm-unlock] SettingHook: forceLayout failed: " + e.getMessage());
            }
        });
    }

    private ViewGroup findVerticalContainer(View root) {
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;

        if (group instanceof android.widget.ScrollView && group.getChildCount() > 0) {
            View child = group.getChildAt(0);
            if (child instanceof LinearLayout && ((LinearLayout) child).getOrientation() == LinearLayout.VERTICAL) {
                return (ViewGroup) child;
            }
        }

        for (int i = 0; i < group.getChildCount(); i++) {
            ViewGroup result = findVerticalContainer(group.getChildAt(i));
            if (result != null) return result;
        }

        if (group.getChildCount() >= 5) {
            if (group instanceof LinearLayout && ((LinearLayout) group).getOrientation() == LinearLayout.VERTICAL) {
                return group;
            }
        }

        return null;
    }

    private View findFirstSettingsRow(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof ViewGroup && findFirstTextView((ViewGroup) child) != null) {
                return child;
            }
        }
        return null;
    }

    private TextView findFirstTextView(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof TextView) return (TextView) child;
            if (child instanceof ViewGroup) {
                TextView result = findFirstTextView((ViewGroup) child);
                if (result != null) return result;
            }
        }
        return null;
    }

    /**
     * Create settings row that matches the app's style.
     */
    private LinearLayout createSettingsRow(Context context, View styleRow) {
        LinearLayout row = new LinearLayout(context);
        row.setTag(SETTINGS_ROW_TAG);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Copy style from existing row if available
        float existingTextSize = 0;
        int existingTextColor = 0;
        int titlePadLeft = 0;

        if (styleRow instanceof ViewGroup) {
            TextView existingTv = findFirstTextView((ViewGroup) styleRow);
            if (existingTv != null) {
                existingTextSize = existingTv.getTextSize();
                existingTextColor = existingTv.getCurrentTextColor();
                titlePadLeft = existingTv.getPaddingLeft();
                // Copy background
                View clickableChild = findClickableChild((ViewGroup) styleRow);
                if (clickableChild != null && clickableChild.getBackground() != null) {
                    Drawable bg = clickableChild.getBackground();
                    row.setBackground(bg.getConstantState() != null ? bg.getConstantState().newDrawable() : bg);
                }
            }
        }

        int padH = dp2px(50);
        int padV = dp2px(20);
        row.setPadding(padH, padV, padH, padV);

        // Title
        titleView = new TextView(context);
        titleView.setTextColor(0xFFFFFFFF); // white for dark RN background
        if (existingTextSize > 0) {
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, existingTextSize);
            titleView.setPadding(titlePadLeft, 0, 0, 0);
        } else {
            titleView.setTextSize(16);
        }
        titleView.setText("NCM Unlock 设置");
        row.addView(titleView);

        // Subtitle
        subView = new TextView(context);
        subView.setTextColor(0xCCFFFFFF); // 80% white
        if (existingTextSize > 0) {
            subView.setTextSize(TypedValue.COMPLEX_UNIT_PX, existingTextSize * 0.8f);
            subView.setPadding(titlePadLeft, 0, 0, 0);
        } else {
            subView.setTextSize(12);
        }
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp2px(4);
        subView.setLayoutParams(subLp);
        subView.setText("当前音质: " + SettingsHelper.getQualityName(SettingsHelper.getAudioQuality()));
        row.addView(subView);

        row.setOnClickListener(v -> showSettingsDialog(context));
        return row;
    }

    private View findClickableChild(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.isClickable()) return child;
            if (child instanceof ViewGroup) {
                View result = findClickableChild((ViewGroup) child);
                if (result != null) return result;
            }
        }
        return null;
    }

    private void showSettingsDialog(Context context) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("NCM Unlock 设置");

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp2px(24), dp2px(16), dp2px(24), dp2px(8));

        // 音质选择
        TextView qualityLabel = new TextView(context);
        qualityLabel.setText("音质选择");
        qualityLabel.setTextSize(15);
        qualityLabel.setTextColor(0xFF333333);
        qualityLabel.getPaint().setFakeBoldText(true);
        layout.addView(qualityLabel);

        RadioGroup radioGroup = new RadioGroup(context);
        radioGroup.setOrientation(RadioGroup.VERTICAL);
        radioGroup.setPadding(0, dp2px(8), 0, dp2px(16));

        int currentQuality = SettingsHelper.getAudioQuality();
        addRadioButton(radioGroup, "标准 128kbps", SettingsHelper.QUALITY_STANDARD, currentQuality);
        addRadioButton(radioGroup, "较高 192kbps", SettingsHelper.QUALITY_HIGHER, currentQuality);
        addRadioButton(radioGroup, "极高 320kbps", SettingsHelper.QUALITY_EXHIGH, currentQuality);
        addRadioButton(radioGroup, "无损 FLAC (最高可用)", SettingsHelper.QUALITY_LOSSLESS, currentQuality);
        layout.addView(radioGroup);

        // Toast 开关
        LinearLayout switchLayout = new LinearLayout(context);
        switchLayout.setOrientation(LinearLayout.HORIZONTAL);
        switchLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView toastLabel = new TextView(context);
        toastLabel.setText("显示替换提示");
        toastLabel.setTextSize(15);
        toastLabel.setTextColor(0xFF333333);
        toastLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        switchLayout.addView(toastLabel);

        Switch toastSwitch = new Switch(context);
        toastSwitch.setChecked(SettingsHelper.isToastEnabled());
        switchLayout.addView(toastSwitch);
        layout.addView(switchLayout);

        builder.setView(layout);
        builder.setPositiveButton("确定", (dialog, which) -> {
            int selectedId = radioGroup.getCheckedRadioButtonId();
            if (selectedId != -1) {
                RadioButton selected = radioGroup.findViewById(selectedId);
                int quality = (int) selected.getTag();
                SettingsHelper.setAudioQuality(quality);
            }
            SettingsHelper.setToastEnabled(toastSwitch.isChecked());
            // Update subtitle
            if (subView != null) {
                subView.setText("当前音质: " + SettingsHelper.getQualityName(SettingsHelper.getAudioQuality()));
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void addRadioButton(RadioGroup group, String text, int quality, int current) {
        RadioButton rb = new RadioButton(context);
        rb.setText(text);
        rb.setTag(quality);
        rb.setTextSize(14);
        rb.setPadding(dp2px(8), dp2px(4), dp2px(8), dp2px(4));
        rb.setId(quality); // 使用 quality 值作为唯一 ID
        if (quality == current) rb.setChecked(true);
        group.addView(rb);
    }

    private void dumpViewTree(Activity activity) {
        try {
            View root = activity.findViewById(android.R.id.content);
            if (root == null) return;
            StringBuilder sb = new StringBuilder();
            dumpView(root, 0, sb);
            String result = sb.toString();
            int pos = 0;
            while (pos < result.length()) {
                int end = Math.min(pos + 4000, result.length());
                XposedBridge.log("[ncm-unlock] DIAG: " + result.substring(pos, end));
                pos = end;
            }
        } catch (Exception ignored) {}
    }

    private void dumpView(View view, int depth, StringBuilder sb) {
        if (depth > 15) return;
        for (int i = 0; i < depth; i++) sb.append("  ");
        String cls = view.getClass().getSimpleName();
        String rid = "";
        try { if (view.getId() > 0) rid = view.getResources().getResourceEntryName(view.getId()); } catch (Exception ignored) {}
        String text = (view instanceof TextView) ? ((TextView) view).getText().toString() : "";
        sb.append(cls);
        if (!rid.isEmpty()) sb.append(" id=").append(rid);
        if (!text.isEmpty()) sb.append(" t=\"").append(text.substring(0, Math.min(text.length(), 20))).append("\"");
        if (view instanceof ViewGroup) sb.append(" ch=").append(((ViewGroup) view).getChildCount());
        sb.append("\n");
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                dumpView(((ViewGroup) view).getChildAt(i), depth + 1, sb);
            }
        }
    }

    private int dp2px(int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
