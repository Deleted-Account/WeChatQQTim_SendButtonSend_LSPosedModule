package com.anonymous.sendbuttonsend;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

public class MainHook extends XposedModule {

    private static final String TAG = "SendButtonSend";

    public static final String PKG_WECHAT = "com.tencent.mm";
    public static final String PKG_QQ = "com.tencent.mobileqq";
    public static final String PKG_TIM = "com.tencent.tim";

    /** 作用域内的全部目标 App；广播发给所有进程，各进程模块自行决定是否响应。 */
    private static final Set<String> TARGET_PACKAGES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(PKG_WECHAT, PKG_QQ, PKG_TIM)));

    private static final String ACTION_SEND = "com.anonymous.sendbuttonsend.SEND";
    /** 诊断用：收到后只 dump 当前视图树，不点击。用于给 QQ/Tim 确定发送按钮的匹配条件。 */
    private static final String ACTION_DUMP = "com.anonymous.sendbuttonsend.DUMP";

    private static volatile Activity sCurrentActivity = null;
    /** 本进程内被 Hook 的目标包名（每进程一份静态状态）。 */
    private static volatile String sPackageName = null;
    /** 上一次 F12 按下时间，用于双击判定（绕过 Edge 的 restrict listen mode 限制）。 */
    private static volatile long sLastF12DownTime = 0L;
    /** F12 双击间隔阈值（毫秒）。 */
    private static final long F12_DOUBLE_CLICK_INTERVAL_MS = 500L;

    /** 已 show 的 Dialog 及其 DecorView。转发等场景的发送按钮位于 Dialog 窗口内，
     *  不在 Activity 的 DecorView 里，需要额外追踪。dismiss 时移除。 */
    private static final Map<Dialog, View> sDialogDecorViews =
            Collections.synchronizedMap(new HashMap<>());

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "loaded in " + param.getProcessName()
                + ", framework: " + getFrameworkName() + " " + getFrameworkVersion()
                + ", api: " + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGES.contains(param.getPackageName())) return;
        sPackageName = param.getPackageName();

        ClassLoader cl = param.getClassLoader();

        try {
            // Hook Application.onCreate：在 Application 创建后注册广播接收器
            Class<?> appClass = Class.forName("android.app.Application", false, cl);
            Method appOnCreate = appClass.getDeclaredMethod("onCreate");
            appOnCreate.setAccessible(true);
            hook(appOnCreate)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object thisObj = chain.getThisObject();
                        if (thisObj instanceof Context) {
                            Context app = ((Context) thisObj).getApplicationContext();
                            if (app != null) {
                                IntentFilter filter = new IntentFilter();
                                filter.addAction(ACTION_SEND);
                                filter.addAction(ACTION_DUMP);
                                BroadcastReceiver receiver = new BroadcastReceiver() {
                                    @Override
                                    public void onReceive(Context context, Intent intent) {
                                        String action = intent.getAction();
                                        if (ACTION_DUMP.equals(action)) {
                                            dumpCurrentViewTree();
                                        } else if (ACTION_SEND.equals(action)) {
                                            clickSendButton();
                                        }
                                    }
                                };
                                try {
                                    app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                                } catch (Throwable t) {
                                    app.registerReceiver(receiver, filter);
                                }
                                log(Log.INFO, TAG, "receiver registered");
                            }
                        }
                        return result;
                    });

            // Hook Activity.onResume：跟踪当前前台 Activity
            Class<?> activityClass = Class.forName("android.app.Activity", false, cl);
            Method activityOnResume = activityClass.getDeclaredMethod("onResume");
            activityOnResume.setAccessible(true);
            hook(activityOnResume)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object thisObj = chain.getThisObject();
                        if (thisObj instanceof Activity) {
                            sCurrentActivity = (Activity) thisObj;
                        }
                        return result;
                    });

            // Hook Activity.dispatchKeyEvent：在 App 内部捕获 F12 双击，直接触发发送。
            // 原因：OPPO/真我系统的 "restrict listen mode" 会拦截 F12 等"未定义键"，
            // 导致 Edge Pro 等外部按键监听器收不到 F12。但 F12 仍会投递到前台 Activity，
            // 所以在 App 进程内 hook dispatchKeyEvent 可绕过该限制。
            Method activityDispatchKeyEvent = activityClass.getDeclaredMethod(
                    "dispatchKeyEvent", KeyEvent.class);
            activityDispatchKeyEvent.setAccessible(true);
            hook(activityDispatchKeyEvent)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object thisObj = chain.getThisObject();
                        List<Object> args = chain.getArgs();
                        if (thisObj instanceof Activity && args != null && !args.isEmpty()
                                && args.get(0) instanceof KeyEvent) {
                            handleKeyEvent((KeyEvent) args.get(0));
                        }
                        return result;
                    });

            // ---- Dialog 支持：转发等场景的 UI 在独立 Dialog 窗口内 ----
            Class<?> dialogClass = Class.forName("android.app.Dialog", false, cl);

            // Hook Dialog.show / dismiss：追踪已显示 Dialog 的 DecorView，
            // 以便在搜索发送按钮时覆盖 Dialog 窗口（全用公开 API，不反射隐藏字段）。
            Method dialogShow = dialogClass.getDeclaredMethod("show");
            dialogShow.setAccessible(true);
            hook(dialogShow)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object thisObj = chain.getThisObject();
                        if (thisObj instanceof Dialog) {
                            try {
                                View decor = ((Dialog) thisObj).getWindow().getDecorView();
                                if (decor != null) {
                                    sDialogDecorViews.put((Dialog) thisObj, decor);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        return result;
                    });

            Method dialogDismiss = dialogClass.getDeclaredMethod("dismiss");
            dialogDismiss.setAccessible(true);
            hook(dialogDismiss)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object thisObj = chain.getThisObject();
                        if (thisObj instanceof Dialog) {
                            sDialogDecorViews.remove(thisObj);
                        }
                        return chain.proceed();
                    });

            // Hook Dialog.dispatchKeyEvent：Dialog 显示时按键走 Dialog 通道，
            // Activity.dispatchKeyEvent 不会被调用，必须在这里也捕获 F12。
            Method dialogDispatchKeyEvent = dialogClass.getDeclaredMethod(
                    "dispatchKeyEvent", KeyEvent.class);
            dialogDispatchKeyEvent.setAccessible(true);
            hook(dialogDispatchKeyEvent)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object thisObj = chain.getThisObject();
                        List<Object> args = chain.getArgs();
                        if (thisObj instanceof Dialog && args != null && !args.isEmpty()
                                && args.get(0) instanceof KeyEvent) {
                            handleKeyEvent((KeyEvent) args.get(0));
                        }
                        return result;
                    });

            log(Log.INFO, TAG, "hooks installed for " + param.getPackageName());
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "failed to install hooks", t);
        }
    }

    /**
     * 处理按键事件：检测 F12 双击并触发发送。
     * Activity 和 Dialog 的 dispatchKeyEvent 共用此逻辑。
     */
    private static void handleKeyEvent(KeyEvent ev) {
        if (ev.getKeyCode() == KeyEvent.KEYCODE_F12
                && ev.getAction() == KeyEvent.ACTION_DOWN) {
            long now = System.currentTimeMillis();
            long last = sLastF12DownTime;
            if (last > 0 && now - last <= F12_DOUBLE_CLICK_INTERVAL_MS) {
                sLastF12DownTime = 0L;
                Log.i(TAG, "F12 double-click -> send");
                clickSendButton();
            } else {
                sLastF12DownTime = now;
            }
        }
    }

    private static void clickSendButton() {
        // 收集所有需要搜索的根 View：Activity 的 DecorView + 所有已显示 Dialog 的 DecorView
        List<View> roots = new ArrayList<>();
        Activity act = sCurrentActivity;
        if (act != null) {
            roots.add(act.getWindow().getDecorView());
        }
        synchronized (sDialogDecorViews) {
            roots.addAll(sDialogDecorViews.values());
        }
        if (roots.isEmpty()) {
            Log.w(TAG, "no current activity or dialog");
            return;
        }

        List<View> cands = new ArrayList<>();
        for (View root : roots) {
            collectSendCandidates(root, cands);
        }

        List<View> visible = new ArrayList<>();
        for (View v : cands) {
            if (v.isShown() && v.isEnabled() && v.isClickable()) {
                visible.add(v);
            }
        }
        if (visible.isEmpty()) {
            Log.w(TAG, "no visible send button (" + cands.size() + " candidate(s))");
            for (View root : roots) {
                dumpViewTree(root, 0);
            }
            return;
        }

        // 多个候选时（如转发页同时有"输入框 Send"和"转发确认 Send"），
        // 优先选不在输入栏里的那个——其父容器不含 EditText 后代。
        View best = null;
        for (View v : visible) {
            if (!isInInputBar(v)) {
                best = v;
                break;
            }
        }
        if (best == null) {
            best = visible.get(0);
        }

        best.performClick();
        Log.i(TAG, "sent via " + describe(best));
    }

    /**
     * 判断按钮是否位于输入栏内。
     * 向上遍历父容器，遇到第一个含多个子 View 的"实际容器"为止；
     * 若该容器含 EditText 后代，说明按钮与输入框同栏（即输入框 Send）。
     */
    private static boolean isInInputBar(View button) {
        ViewParent p = button.getParent();
        while (p instanceof View) {
            View pv = (View) p;
            if (hasEditTextDescendant(pv)) return true;
            if (pv instanceof ViewGroup && ((ViewGroup) pv).getChildCount() > 1) break;
            p = pv.getParent();
        }
        return false;
    }

    private static boolean hasEditTextDescendant(View view) {
        if (view instanceof EditText) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasEditTextDescendant(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private static void dumpCurrentViewTree() {
        Activity act = sCurrentActivity;
        if (act == null) {
            Log.w(TAG, "dump: no current activity");
            return;
        }
        Log.i(TAG, "dump: activity=" + act.getClass().getName()
                + " pkg=" + act.getPackageName());
        dumpViewTree(act.getWindow().getDecorView(), 0);
    }

    private static void dumpViewTree(View view, int depth) {
        if (depth > 30) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(view.getClass().getSimpleName());
        if (view instanceof TextView) {
            CharSequence t = ((TextView) view).getText();
            if (t != null && t.length() > 0) sb.append(" text=\"").append(t).append("\"");
        }
        CharSequence desc = view.getContentDescription();
        if (desc != null && desc.length() > 0) sb.append(" desc=\"").append(desc).append("\"");
        String id = "?";
        try {
            id = view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable ignored) {
        }
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        sb.append(" id=").append(id)
                .append(" clickable=").append(view.isClickable())
                .append(" enabled=").append(view.isEnabled())
                .append(" shown=").append(view.isShown())
                .append(" bounds=[").append(loc[0]).append(",").append(loc[1])
                .append(" ").append(view.getWidth()).append("x").append(view.getHeight()).append("]");
        Log.d(TAG, sb.toString());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                dumpViewTree(group.getChildAt(i), depth + 1);
            }
        }
    }

    private static boolean isSendText(CharSequence cs) {
        if (cs == null) return false;
        String s = cs.toString().trim();
        return "发送".equals(s) || "Send".equalsIgnoreCase(s);
    }

    /**
     * 按 App 区分发送按钮的判定条件（各 App 控件实现不同，勿照搬）：
     * - 微信：文字/desc 为 "Send"/"发送" 的可见可点击控件（id 混淆，只能靠文字）。
     * - QQ/Tim：AIO 输入栏的自定义 View {@code AIOInputSendBtn}，id=send_btn，
     *   不是 TextView、无文字可配——按类名/id 匹配，文字匹配作兜底。
     */
    private static boolean isSendView(View view) {
        String pkg = sPackageName;
        if (PKG_QQ.equals(pkg) || PKG_TIM.equals(pkg)) {
            String cls = view.getClass().getName();
            if (cls.contains("SendBtn")) return true;
            try {
                if ("send_btn".equals(
                        view.getResources().getResourceEntryName(view.getId()))) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        if (view instanceof TextView && isSendText(((TextView) view).getText())) {
            return true;
        }
        return isSendText(view.getContentDescription());
    }

    private static void collectSendCandidates(View view, List<View> out) {
        if (isSendView(view) && !out.contains(view)) {
            out.add(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectSendCandidates(group.getChildAt(i), out);
            }
        }
    }

    private static String describe(View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        String id = "?";
        try {
            id = v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable ignored) {
        }
        String text = (v instanceof TextView) ? String.valueOf(((TextView) v).getText()) : "";
        return v.getClass().getSimpleName() + " id=" + id
                + " text=\"" + text + "\" desc=\"" + v.getContentDescription() + "\""
                + " clickable=" + v.isClickable() + " enabled=" + v.isEnabled() + " shown=" + v.isShown()
                + " bounds=[" + loc[0] + "," + loc[1] + " " + v.getWidth() + "x" + v.getHeight() + "]";
    }
}
