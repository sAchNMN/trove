package app.devicesorter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class MainActivity extends Activity {
    private static final int SOURCE = 10, TARGET = 11, AUTH = 13, BROAD = 14, DIRECT = 15, TRASH = 16;
    private static final String VIEW_SCROLL = SortDraft.SCROLL, VIEW_WIZARD = SortDraft.WIZARD;
    private static final int SCREEN_HUB = 0, SCREEN_SORT = 1, SCREEN_TOOLS = 2;
    /** Height of the floating tab bar; the pages keep this much clear at the bottom. */
    private static final int PAGE_BOTTOM = BottomNav.HEIGHT + 48;
    /** After the last upward scroll event, wait this long before revealing the bar. */
    private static final long NAV_REVEAL_DELAY = 200L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable revealNav = this::showBottomNav;
    private int windowInsetTop, windowInsetBottom;
    private final SortDraft draft = new SortDraft();
    private final List<View> controls = new ArrayList<>();
    private SharedPreferences prefs;
    private DraftStore store;
    private LinearLayout sourceList, groupList, discoveredList;
    private TextView targetLabel, selection, mediaScope;
    private TextView summaryPhotos, summaryVideos, summaryPending;
    private Button scan, trial, execute;
    private TextView strategy;
    private RadioButton copyMode;
    private ImageView targetChooser;
    private ProgressBar progress;
    private ImageButton cancel;
    private int revision = -1;
    private LogDialog logs;
    private final ArrayDeque<String> authQueue = new ArrayDeque<>();
    private String authTarget = "";
    private boolean scannedAll;
    private boolean libraryAfterScan;
    private int libraryBaseline = -1;
    private final Map<String, CheckBox> discoveredChecks = new LinkedHashMap<>();
    private final Map<String, CheckBox> groupChecks = new LinkedHashMap<>();
    private final Runnable groupRefresh = () -> { if (!Session.running) refreshGroupCounts(); };
    private LinearLayout discoveryHeader;
    private TextView discoveryTitle;
    private Ui.Action selectAll;
    private Button pendingButton, resetButton;
    private boolean syncing;
    private RadioButton trashMode;
    private final List<String> directUris = new ArrayList<>();
    private boolean duplicateRequested;
    private int duplicateBaseline = -1;
    private Trash.Pending pendingTrash;
    private boolean trashPrompted;
    private final java.util.ArrayDeque<Trash.Item> trashQueue = new java.util.ArrayDeque<>();
    private final List<Trash.Item> trashChunk = new ArrayList<>();
    private final Set<String> trashedUris = new HashSet<>();
    private int trashTotal, trashDone, trashFailed;
    private String trashAction = "";
    private boolean resumed;
    private boolean mediaObserverRegistered;
    private String mediaScopeState = "";

    /**
     * MediaStore notifies inserts/updates/deletions. The sync is debounced because a
     * camera save or a batch copy can emit many notifications in a row, and it uses
     * the generation check to skip work when nothing actually changed.
     */
    private final ContentObserver mediaObserver = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) {
            onChange(selfChange, null);
        }
        @Override public void onChange(boolean selfChange, Uri uri) {
            scheduleMediaCheck(2000);
        }
    };

    private final Runnable mediaCheck = new Runnable() {
        @Override public void run() {
            if (!resumed || !scannedAll || !MediaDiscovery.accessible(MainActivity.this)) return;
            if (Session.running) { handler.postDelayed(this, 3000); return; }
            if (!MediaCache.hasBaseline(MainActivity.this)) return;
            if (MediaCache.fresh(MainActivity.this)) return;
            startMediaSync();
        }
    };

    // Two-entry shell: the hub reserves space for future tools, while the sort
    // screen owns the existing file workflow. Keeping every panel built once
    // means switching views or steps never loses widget state.
    private int screen = SCREEN_HUB;
    private View hubScreen, sortScreen, toolsScreen;
    private FrameLayout root;
    private LinearLayout summaryBox;
    private ScrollView hubScroll, sortScroll, toolsScroll;
    private LinearLayout sortPage, sortContent, hubPage, toolsPage, stepOne, stepTwo, stepThree;
    private LinearLayout wizardFooter, folderBlock, folderBody, stepHeader;
    private TextView stepBadge, stepTitle, stepHint, folderSummary, review, wizardBlocker;
    private View sectionOne, sectionTwo, sectionThree;
    private int authorizedFolders;
    private TextView hubStatus, toolsStatus, status;
    private Ui.Action hubDraft;
    private Button hubEnter, nextStep;
    private Ui.Action hubDiscard, hubModeToggle, modeToggle, prevStep;
    private LinearLayout wizardActions;
    private TextView folderToggle;
    private Button scanAll;
    private View libraryButton, recallButton, duplicatesButton;
    private ImageButton permissionButton;
    private RadioButton movingMode;
    private CheckBox locationCheck;
    private ProgressBar hubProgress, toolsProgress;
    private BottomNav nav;
    private HintPopup hintPopup;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (status != null && !status.getText().toString().equals("● " + Session.status)) {
                status.setText("● " + Session.status);
            }
            if (hubStatus != null && !hubStatus.getText().toString().equals(Session.status)) {
                hubStatus.setText(Session.status);
            }
            if (toolsStatus != null && !toolsStatus.getText().toString().equals(Session.status)) {
                toolsStatus.setText(Session.status);
            }
            if (Session.rescanRequested && !Session.running) {
                Session.rescanRequested = false;
                // Recycle-bin changes are small and happen while this page is
                // visible; an incremental background sync avoids a notification.
                if (scannedAll && MediaCache.hasBaseline(MainActivity.this)) startMediaSync();
                else startDiscovery();
            }
            boolean busy = Session.running;
            if (progress != null) progress.setVisibility(busy ? View.VISIBLE : View.GONE);
            if (hubProgress != null) hubProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
            if (toolsProgress != null) toolsProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
            if (cancel != null) cancel.setVisibility(busy ? View.VISIBLE : View.GONE);
            for (View view : controls) {
                if (view.isEnabled() != !busy) view.setEnabled(!busy);
                float alpha = busy ? .45f : 1f;
                if (view.getAlpha() != alpha) view.setAlpha(alpha);
            }
            if (groupList != null) enableChildren(groupList, !busy);
            if (discoveredList != null) enableChildren(discoveredList, !busy);
            if (selectAll != null) {
                boolean enabled = !busy && !discoveredChecks.isEmpty();
                if (selectAll.isEnabled() != enabled) selectAll.setEnabled(enabled);
            }
            if (scan != null) {
                boolean enabled = !busy && !draft.sources.isEmpty();
                if (scan.isEnabled() != enabled) scan.setEnabled(enabled);
            }
            boolean ready = !busy && (draft.inPlace || !draft.target.isEmpty()) && !draft.selected.isEmpty()
                    && !Session.items.isEmpty();
            if (trial != null) {
                if (trial.isEnabled() != ready) trial.setEnabled(ready);
                float alpha = ready ? 1f : .4f;
                if (trial.getAlpha() != alpha) trial.setAlpha(alpha);
            }
            if (execute != null) {
                setPrimaryEnabled(execute, ready);
            }
            if (scan != null) {
                float alpha = scan.isEnabled() ? 1f : .4f;
                if (scan.getAlpha() != alpha) scan.setAlpha(alpha);
            }
            if (revision != Session.revision) {
                revision = Session.revision;
                renderDiscovered();
                renderGroups();
                updateHub();
            }
            updateMediaScope();
            updateWizardFooter();
            if (libraryAfterScan && !busy && Session.revision != libraryBaseline) {
                libraryAfterScan = false;
                showLibrary(false);
            }
            maybeShowDuplicates();
            maybePromptTrash();
            handler.postDelayed(this, 400);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        setupEdgeToEdge();
        int startScreen = SCREEN_HUB;
        logs = new LogDialog(this);
        if (state != null) {
            logs.restore(state);
            authTarget = state.getString("authTarget", "");
            ArrayList<String> pendingAuth = state.getStringArrayList("authQueue");
            if (pendingAuth != null) authQueue.addAll(pendingAuth);
            startScreen = state.getInt("screen", SCREEN_HUB);
        }
        prefs = getSharedPreferences("settings", 0);
        store = new DraftStore(prefs);
        store.load(draft, state);
        scannedAll = prefs.getBoolean("scannedAll", false);
        Session.load(this);
        Session.loadDuplicates(this);
        build();
        renderSources();
        renderDiscovered();
        renderGroups();
        updateHub();
        showScreen(startScreen);
        if (!Session.running && prefs.getBoolean("jobStarted", false)) {
            Session.status = "上次任务可能被中断，请检查操作记录和目标目录；不会自动继续删除文件";
            prefs.edit().putBoolean("jobStarted", false).apply();
        }
    }

    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        hubScreen = buildHub();
        sortScreen = buildSort();
        toolsScreen = buildTools();
        root.addView(hubScreen, new FrameLayout.LayoutParams(-1, -1));
        root.addView(sortScreen, new FrameLayout.LayoutParams(-1, -1));
        root.addView(toolsScreen, new FrameLayout.LayoutParams(-1, -1));
        hubScreen.setVisibility(View.GONE);
        sortScreen.setVisibility(View.GONE);
        toolsScreen.setVisibility(View.GONE);
        nav = new BottomNav(this, new BottomNav.Host() {
            @Override public void open(int page) { openTab(page); }

            @Override public void scrollToTop(int page) { MainActivity.this.scrollToTop(page); }
        });
        nav.addTab(R.drawable.ic_nav_home, "首页", SCREEN_HUB);
        nav.addTab(R.drawable.ic_nav_sort, "整理", SCREEN_SORT);
        nav.addTab(R.drawable.ic_nav_media, "媒体库", SCREEN_TOOLS);
        FrameLayout.LayoutParams navParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        navParams.bottomMargin = dp(18);
        root.addView(nav.view(), navParams);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            applyWindowInsets(insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();
    }

    /** Draw behind the status and gesture bars, then inset the scrolling content itself. */
    private void setupEdgeToEdge() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 28) window.setNavigationBarDividerColor(Color.TRANSPARENT);
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!Ui.NIGHT) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private void applyWindowInsets(int top, int bottom) {
        if (top == windowInsetTop && bottom == windowInsetBottom) return;
        windowInsetTop = top;
        windowInsetBottom = bottom;
        if (hubPage != null) hubPage.setPadding(dp(16), dp(24) + top, dp(16), dp(PAGE_BOTTOM) + bottom);
        if (sortContent != null) sortContent.setPadding(0, top, 0, bottom);
        if (toolsPage != null) toolsPage.setPadding(dp(16), dp(24) + top, dp(16), dp(PAGE_BOTTOM) + bottom);
        if (nav != null) nav.setBottomInset(dp(18) + bottom);
    }

/** Feature home: only decisions live here; explanations sit behind tap hints. */
    private View buildHub() {
        hubScroll = new ScrollView(this);
        hubScroll.setFillViewport(true);
        hubScroll.setBackgroundColor(Color.TRANSPARENT);
        hubScroll.setClipToPadding(false);
        hubScroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> onScreenScroll(v, y - oldY));
        hubPage = column();
        hubPage.setPadding(dp(16), dp(24), dp(16), dp(PAGE_BOTTOM));
        hubScroll.addView(hubPage);

        LinearLayout header = row();
        LinearLayout titles = column();
        TextView title = text("归藏", 20, Ui.INK, true);
        titles.addView(title);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(icon(R.drawable.ic_history, "操作记录", v -> logs.openLatest()));
        hubPage.addView(header);
        space(hubPage, 16);

        LinearLayout statusCard = cardBox();
        statusCard.setPadding(dp(16), dp(14), dp(12), dp(14));
        LinearLayout statusRow = row();
        View dot = new View(this);
        dot.setBackground(Ui.fill(Ui.ACCENT, dp(4)));
        statusRow.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        LinearLayout statusBody = column();
        statusBody.setPadding(dp(12), 0, dp(8), 0);
        statusBody.addView(Ui.label(this, "当前状态", 12, Ui.MUTED, false));
        hubStatus = text(Session.status, 13, Ui.INK, false);
        hubStatus.setMaxLines(2);
        hubStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        hubStatus.setLineSpacing(0f, 1.4f);
        hubStatus.setPadding(0, dp(2), 0, 0);
        statusBody.addView(hubStatus);
        statusRow.addView(statusBody, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView statusChevron = new ImageView(this);
        statusChevron.setImageResource(R.drawable.ic_chevron);
        statusChevron.setColorFilter(Ui.FAINT);
        statusRow.addView(statusChevron, new LinearLayout.LayoutParams(dp(16), dp(16)));
        // A one-line status is only ~18dp tall; the row must still be a 48dp target.
        statusRow.setMinimumHeight(dp(48));
        statusRow.setClickable(true);
        statusRow.setFocusable(true);
        statusRow.setOnClickListener(v -> showStatusDialog());
        statusCard.addView(statusRow);
        hubProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        hubProgress.setIndeterminate(true);
        hubProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        hubProgress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        hubProgress.setVisibility(Session.running ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, dp(3));
        hp.setMargins(0, dp(Ui.S3), 0, 0);
        statusCard.addView(hubProgress, hp);
        addCard(hubPage, statusCard);

        sectionLabel(hubPage, "整理");
        LinearLayout sortCard = cardBox();
        LinearLayout sortHead = row();
        sortHead.addView(text("文件移动分类", 16, Ui.INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        sortCard.addView(sortHead);
        TextView sortDesc = text("按设备与拍摄时间归入真实文件夹，先校验副本，再决定是否清理原件。", 14, Ui.MUTED, false);
        sortDesc.setLineSpacing(0f, 1.45f);
        sortDesc.setPadding(0, dp(6), 0, dp(16));
        sortCard.addView(sortDesc);

        // One short line instead of three pills: at 347dp the pills squeezed "确认执行"
        // onto two lines, which is the exact defect this pass is removing.
        TextView flow = text("01 选目录  →  02 勾选分类  →  03 确认执行", 12, Ui.MUTED, false);
        flow.setPadding(0, dp(2), 0, 0);
        sortCard.addView(flow);

        LinearLayout modeRow = row();
        modeRow.setPadding(0, dp(16), 0, 0);
        // The draft state is a link, not a paragraph: its own 48dp slot keeps the tap
        // target while the text stays 13sp and left aligned.
        hubDraft = new Ui.Action(this, "", 13, Color.TRANSPARENT, Ui.INK_SOFT);
        hubDraft.alignStart();
        hubDraft.setOnClickListener(v -> enterSort());
        LinearLayout.LayoutParams draftParams = new LinearLayout.LayoutParams(0, -2, 1);
        draftParams.setMargins(0, 0, dp(Ui.S2), 0);
        modeRow.addView(hubDraft, draftParams);
        hubModeToggle = smallButton("", Ui.SOFT, v -> toggleViewMode());
        hubModeToggle.setTextColor(Ui.INK_SOFT);
        hubModeToggle.setTextSize(12);
        hubModeToggle.setOnLongClickListener(v -> {
            showHint(v, "单页：从 01 到 03 连续滚动。分步：用底部“上一步 / 下一步”，每次只显示一步。");
            return true;
        });
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(-2, -2);
        toggleParams.setMargins(dp(Ui.S2), 0, 0, 0);
        modeRow.addView(hubModeToggle, toggleParams);
        sortCard.addView(modeRow);

        hubEnter = button("开始设置", Ui.PRIMARY, v -> enterSort());
        hubEnter.setMinHeight(dp(52));
        LinearLayout.LayoutParams enterParams = new LinearLayout.LayoutParams(-1, -2);
        enterParams.setMargins(0, dp(Ui.S4), 0, 0);
        sortCard.addView(hubEnter, enterParams);

        hubDiscard = smallButton("舍弃未完成设置", Color.TRANSPARENT, v -> confirmDiscard());
        hubDiscard.setTextColor(Ui.MUTED);
        hubDiscard.setVisibility(View.GONE);
        LinearLayout discardRow = row();
        discardRow.setGravity(Gravity.END);
        discardRow.setPadding(0, dp(Ui.S2), 0, 0);
        discardRow.addView(hubDiscard);
        sortCard.addView(discardRow);
        addCard(hubPage, sortCard);

        sectionLabel(hubPage, "媒体");
        LinearLayout toolsCard = cardBox();
        LinearLayout toolsHead = row();
        toolsHead.addView(text("媒体库与查重", 16, Ui.INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        toolsCard.addView(toolsHead);
        TextView toolsDesc = text("浏览全部照片与视频，找回散落在其他目录的媒体，或清理完全重复的副本。", 14, Ui.MUTED, false);
        toolsDesc.setLineSpacing(0f, 1.45f);
        toolsDesc.setPadding(0, dp(6), 0, dp(16));
        toolsCard.addView(toolsDesc);
        Button openTools = quietButton("打开媒体工具", v -> enterTools());
        toolsCard.addView(openTools, new LinearLayout.LayoutParams(-1, -2));
        addCard(hubPage, toolsCard);

        TextView footer = text("离线 · 本地处理 · 不直接删除", 12, Ui.MUTED, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(16), 0, 0);
        hubPage.addView(footer);
        return hubScroll;
    }

    private View buildTools() {
        toolsScroll = new ScrollView(this);
        toolsScroll.setFillViewport(true);
        toolsScroll.setBackgroundColor(Color.TRANSPARENT);
        toolsScroll.setClipToPadding(false);
        toolsScroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> onScreenScroll(v, y - oldY));
        toolsPage = column();
        toolsPage.setPadding(dp(16), dp(24), dp(16), dp(PAGE_BOTTOM));
        toolsScroll.addView(toolsPage);

        LinearLayout header = row();
        LinearLayout titles = column();
        TextView title = text("媒体库与查重", 20, Ui.INK, true);
        titles.addView(title);
        TextView sub = text("浏览 · 找回 · 查重", 13, Ui.MUTED, false);
        sub.setPadding(0, dp(4), 0, 0);
        titles.addView(sub);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(icon(R.drawable.ic_history, "操作记录", v -> logs.openLatest()));
        toolsPage.addView(header);
        space(toolsPage, 16);

        LinearLayout statusCard = cardBox();
        statusCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        statusCard.addView(Ui.label(this, "当前状态", 12, Ui.MUTED, false));
        toolsProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        toolsProgress.setIndeterminate(true);
        toolsProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        toolsProgress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        toolsProgress.setVisibility(Session.running ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams toolsProgressParams = new LinearLayout.LayoutParams(-1, dp(3));
        toolsProgressParams.setMargins(0, dp(Ui.S3), 0, 0);
        statusCard.addView(toolsProgress, toolsProgressParams);
        toolsStatus = text(Session.status, 13, Ui.INK_SOFT, false);
        toolsStatus.setLineSpacing(0f, 1.45f);
        toolsStatus.setPadding(0, dp(4), 0, dp(4));
        toolsStatus.setMinHeight(dp(48));
        toolsStatus.setGravity(Gravity.CENTER_VERTICAL);
        toolsStatus.setClickable(true);
        toolsStatus.setFocusable(true);
        toolsStatus.setOnClickListener(v -> showStatusDialog());
        statusCard.addView(toolsStatus);
        addCard(toolsPage, statusCard);

        sectionLabel(toolsPage, "媒体工具");
        LinearLayout listCard = cardBox();
        listCard.setPadding(dp(4), dp(4), dp(4), dp(4));
        libraryButton = toolRow(R.drawable.ic_photo, "浏览全部媒体",
                "全部照片与视频", v -> openAllMedia());
        recallButton = toolRow(R.drawable.ic_restore, "找回相册外的媒体",
                "复制回相机目录并校验", v -> showLibrary(true));
        duplicatesButton = toolRow(R.drawable.ic_copy, "查找重复媒体",
                "按内容精确比对", v -> startDuplicates());
        listCard.addView(libraryButton);
        LinearLayout.LayoutParams recallParams2 = new LinearLayout.LayoutParams(-1, -2);
        recallParams2.setMargins(0, dp(Ui.S2), 0, 0);
        listCard.addView(recallButton, recallParams2);
        LinearLayout.LayoutParams duplicatesParams = new LinearLayout.LayoutParams(-1, -2);
        duplicatesParams.setMargins(0, dp(Ui.S2), 0, 0);
        listCard.addView(duplicatesButton, duplicatesParams);
        addCard(toolsPage, listCard);
        controls.add(libraryButton);
        controls.add(recallButton);
        controls.add(duplicatesButton);

        sectionLabel(toolsPage, "维护");
        LinearLayout maintain = cardBox();
        LinearLayout maintainHead = row();
        maintainHead.addView(text("媒体索引", 16, Ui.INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        maintainHead.addView(hint("只有当设备名、拍摄日期读错，或拍照 App 改写原文件 EXIF、"
                + "但大小与修改时间未变时，才需要重建。平时打开媒体库会自动增量同步。"));
        maintain.addView(maintainHead);
        TextView maintainDesc = text("增量同步拍摄信息；重建只在怀疑元数据过期时使用。", 13, Ui.MUTED, false);
        maintainDesc.setLineSpacing(0f, 1.45f);
        maintainDesc.setPadding(0, dp(6), 0, dp(14));
        maintain.addView(maintainDesc);
        Button rebuild = quietButton("重建媒体索引（重新读取拍摄信息）", v -> confirmRebuild());
        rebuild.setEnabled(!Session.running);
        rebuild.setAlpha(rebuild.isEnabled() ? 1f : .4f);
        controls.add(rebuild);
        maintain.addView(rebuild, new LinearLayout.LayoutParams(-1, -2));
        addCard(toolsPage, maintain);
        return toolsScroll;
    }

private View buildSort() {
        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout shell = column();
        screen.addView(shell, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = column();
        header.setBackgroundColor(Ui.CARD);
        header.setPadding(dp(16), dp(14), dp(8), dp(10));

        LinearLayout topRow = row();
        LinearLayout titles = column();
        titles.addView(text("文件移动分类", 20, Ui.INK, true));
        topRow.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        modeToggle = smallButton("单页", Ui.SOFT, v -> toggleViewMode());
        modeToggle.setTextColor(Ui.INK_SOFT);
        modeToggle.setTextSize(12);
        modeToggle.setContentDescription("当前单页视图，点按切换为分步");
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(-2, -2);
        modeParams.setMargins(0, 0, dp(Ui.S2), 0);
        topRow.addView(modeToggle, modeParams);
        topRow.addView(icon(R.drawable.ic_history, "操作记录", v -> logs.openLatest()));
        header.addView(topRow);

        LinearLayout statusRow = row();
        statusRow.setPadding(0, dp(6), dp(8), 0);
        LinearLayout statusSlot = row();
        statusSlot.setMinimumHeight(dp(48));
        statusSlot.setClickable(true);
        statusSlot.setFocusable(true);
        statusSlot.setContentDescription("查看当前状态");
        status = text("● " + Session.status, 12, Ui.MUTED, false);
        status.setMaxLines(2);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setLineSpacing(0f, 1.4f);
        statusSlot.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        statusSlot.setOnClickListener(v -> showStatusDialog());
        statusRow.addView(statusSlot, new LinearLayout.LayoutParams(0, -2, 1));
        // A long status set before the view is attached keeps a single-line layout and
        // gets clipped; re-applying it once attached makes maxLines(2) take effect.
        status.post(() -> {
            if (status != null && Session.status != null) status.setText("● " + Session.status);
        });
        cancel = icon(R.drawable.ic_close, "取消任务", v -> {
            Session.cancel = true;
            status.setText("● 正在取消，请等待当前读写结束");
        });
        cancel.setVisibility(View.GONE);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        cancelParams.setMargins(dp(Ui.S2), 0, 0, 0);
        statusRow.addView(cancel, cancelParams);
        header.addView(statusRow);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        progress.setVisibility(View.GONE);
        header.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        stepHeader = column();
        stepHeader.setBackgroundColor(Ui.SOFT_DARK);
        stepHeader.setPadding(dp(16), dp(12), dp(16), dp(12));
        stepBadge = Ui.mono(this, "", 11, Ui.ACCENT, true);
        stepBadge.setLetterSpacing(0.08f);
        stepBadge.setGravity(Gravity.CENTER);
        stepBadge.setPadding(dp(8), dp(4), dp(8), dp(4));
        stepBadge.setBackground(Ui.fill(Ui.ACCENT_SOFT, dp(8)));
        stepTitle = text("", 16, Ui.INK, true);
        stepTitle.setPadding(0, dp(8), 0, 0);
        stepHint = text("", 13, Ui.INK_SOFT, false);
        stepHint.setLineSpacing(0f, 1.45f);
        stepHint.setPadding(0, dp(4), 0, 0);
        stepHeader.addView(stepBadge, new LinearLayout.LayoutParams(-2, -2));
        stepHeader.addView(stepTitle);
        stepHeader.addView(stepHint);

        FrameLayout body = new FrameLayout(this);
        sortScroll = new ScrollView(this);
        sortScroll.setFillViewport(true);
        sortScroll.setBackgroundColor(Color.TRANSPARENT);
        sortScroll.setClipToPadding(false);
        sortScroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> onScreenScroll(v, y - oldY));
        sortPage = column();
        sortPage.setPadding(dp(16), dp(16), dp(16), 0);

        summaryBox = summaryCard();
        addCard(sortPage, summaryBox);

        stepOne = buildStepOne();
        stepTwo = buildStepTwo();
        stepThree = buildStepThree();
        sortPage.addView(stepOne);
        sortPage.addView(stepTwo);
        sortPage.addView(stepThree);

        // The page header, the step header and the wizard footer all live in the same
        // scroll content as the cards: nothing is pinned above or below the list.
        LinearLayout scrollContent = column();
        sortContent = scrollContent;
        scrollContent.addView(header, new LinearLayout.LayoutParams(-1, -2));
        scrollContent.addView(stepHeader, new LinearLayout.LayoutParams(-1, -2));
        scrollContent.addView(sortPage, new LinearLayout.LayoutParams(-1, -2));
        View stretch = new View(this);
        scrollContent.addView(stretch, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout footerWrap = column();
        footerWrap.setPadding(dp(16), dp(Ui.S2), dp(16), dp(PAGE_BOTTOM));
        wizardFooter = buildWizardFooter();
        wizardFooter.setVisibility(View.GONE);
        footerWrap.addView(wizardFooter, new LinearLayout.LayoutParams(-1, -2));
        scrollContent.addView(footerWrap, new LinearLayout.LayoutParams(-1, -2));
        sortScroll.addView(scrollContent);
        body.addView(sortScroll, new FrameLayout.LayoutParams(-1, -1));

        shell.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        return screen;
    }

    private LinearLayout summaryCard() {
        LinearLayout card = cardBox();
        card.setPadding(dp(8), dp(16), dp(8), dp(16));
        LinearLayout row = row();
        summaryPhotos = text("0", 28, Ui.INK, true);
        summaryVideos = text("0", 28, Ui.INK, true);
        summaryPending = text("0", 28, Ui.INK, true);
        row.addView(statCell("照片", summaryPhotos), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(statDivider());
        row.addView(statCell("视频", summaryVideos), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(statDivider());
        row.addView(statCell("待确认", summaryPending), new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row);
        return card;
    }

    private LinearLayout statCell(String label, TextView value) {
        LinearLayout cell = column();
        cell.setGravity(Gravity.CENTER);
        value.setGravity(Gravity.CENTER);
        cell.addView(value);
        TextView caption = Ui.label(this, label, 12, Ui.MUTED, false);
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(0, dp(4), 0, 0);
        cell.addView(caption);
        return cell;
    }

    private View statDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(1), dp(28));
        divider.setLayoutParams(params);
        return divider;
    }

    private LinearLayout buildStepOne() {
        LinearLayout panel = column();
        panel.setPadding(0, 0, 0, 0);
        sectionOne = section(panel, "01", "选择整理目录");

        LinearLayout scanCard = cardBox();
        scanAll = button("扫描全部照片 / 视频", Ui.PRIMARY, v -> startDiscovery());
        scanAll.setMinHeight(dp(52));
        scanAll.setOnLongClickListener(v -> { confirmRebuild(); return true; });
        controls.add(scanAll);
        scanCard.addView(scanAll, new LinearLayout.LayoutParams(-1, -2));

        Button broadDcim = quietButton("授权整个 DCIM", v -> authorizeBroad("DCIM"));
        Button broadPictures = quietButton("授权整个 Pictures", v -> authorizeBroad("Pictures"));
        controls.add(broadDcim);
        controls.add(broadPictures);
        LinearLayout.LayoutParams broadParams = new LinearLayout.LayoutParams(-1, -2);
        broadParams.setMargins(0, dp(Ui.S2), 0, 0);
        scanCard.addView(broadDcim, broadParams);
        LinearLayout.LayoutParams picturesParams = new LinearLayout.LayoutParams(-1, -2);
        picturesParams.setMargins(0, dp(Ui.S2), 0, 0);
        scanCard.addView(broadPictures, picturesParams);

        View allMedia = toolRow(R.drawable.ic_photo, "浏览全部媒体",
                "打开系统相册里的全部照片与视频", v -> openAllMedia());
        controls.add(allMedia);
        LinearLayout.LayoutParams allMediaParams = new LinearLayout.LayoutParams(-1, -2);
        allMediaParams.setMargins(0, dp(Ui.S2), 0, 0);
        scanCard.addView(allMedia, allMediaParams);

        mediaScope = text("", 12, Ui.INK_SOFT, false);
        mediaScope.setPadding(0, dp(Ui.S2), 0, dp(Ui.S2));
        mediaScope.setMinHeight(dp(48));
        mediaScope.setGravity(Gravity.CENTER_VERTICAL);
        mediaScope.setClickable(true);
        mediaScope.setFocusable(true);
        mediaScope.setOnClickListener(v -> {
            if (!MediaDiscovery.full(this)) {
                libraryAfterScan = true;
                libraryBaseline = Session.revision;
                requestMediaPermissions();
            }
        });
        scanCard.addView(mediaScope);

        // The whole row toggles: the Switch itself is a 52x32 visual inside a 48dp
        // slot and no longer receives touches, so there is exactly one target here.
        Ui.SwitchRow archiveRow = new Ui.SwitchRow(this, "包含已归档的文件",
                "重新整理「分类」里已整理过的文件", prefs.getBoolean("includeArchived", false));
        archiveRow.toggle.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        archiveRow.toggle.setTrackTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT_SOFT));
        archiveRow.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean("includeArchived", checked).apply();
            Ui.toast(this, checked
                    ? "已开启：重新扫描后可以看到「分类」里的文件"
                    : "已关闭：重新扫描会跳过「分类」里的文件");
        });
        LinearLayout.LayoutParams archiveParams = new LinearLayout.LayoutParams(-1, -2);
        archiveParams.setMargins(0, dp(Ui.S3), 0, 0);
        scanCard.addView(archiveRow, archiveParams);

        LinearLayout sourceHeader = row();
        sourceHeader.setPadding(0, dp(Ui.S4), 0, 0);
        sourceHeader.addView(text("补充扫描目录", 12, Ui.MUTED, true), new LinearLayout.LayoutParams(0, -2, 1));
        sourceHeader.addView(icon(R.drawable.ic_add, "添加来源目录", v -> pick(SOURCE)));
        scanCard.addView(sourceHeader);
        sourceList = column();
        scanCard.addView(sourceList);

        scan = quietButton("扫描这些目录", v -> startScan());
        LinearLayout.LayoutParams scanParams = new LinearLayout.LayoutParams(-1, -2);
        scanParams.setMargins(0, dp(Ui.S2), 0, 0);
        scanCard.addView(scan, scanParams);
        addCard(panel, scanCard);

        folderBlock = cardBox();
        folderBlock.setPadding(dp(14), dp(12), dp(10), dp(10));
        LinearLayout folderHeader = row();
        folderHeader.setMinimumHeight(dp(56));
        folderHeader.setPadding(0, dp(Ui.S1), 0, dp(Ui.S1));
        folderHeader.setBackground(Ui.ripple(Ui.RIPPLE_DARK,
                Ui.fill(Color.TRANSPARENT, dp(Ui.R_CONTROL))));
        LinearLayout folderTitles = column();
        folderSummary = text("", 13, Ui.INK, true);
        folderTitles.addView(folderSummary);
        folderHeader.addView(folderTitles, new LinearLayout.LayoutParams(0, -2, 1));
        // The label is part of the row; the row owns the only tap target so the old
        // 48dp button nested inside a clickable header can no longer steal taps.
        folderToggle = text("展开", 12, Ui.INK_SOFT, true);
        folderToggle.setPadding(dp(Ui.S3), 0, 0, 0);
        folderHeader.addView(folderToggle, new LinearLayout.LayoutParams(-2, -2));
        folderHeader.setClickable(true);
        folderHeader.setFocusable(true);
        folderHeader.setContentDescription("发现目录，点按展开或收起");
        folderHeader.setOnClickListener(v -> toggleFolders());
        folderBlock.addView(folderHeader);

        folderBody = column();
        discoveryHeader = row();
        discoveryHeader.setGravity(Gravity.END);
        discoveryHeader.setPadding(0, dp(Ui.S2), 0, dp(Ui.S2));
        discoveryTitle = text("选择要整理的目录", 12, Ui.MUTED, false);
        discoveryTitle.setVisibility(View.GONE);
        discoveryHeader.addView(discoveryTitle, new LinearLayout.LayoutParams(0, -2, 1));
        selectAll = smallButton("全选", Ui.SOFT, v -> toggleAllDiscovered());
        selectAll.setTextColor(Ui.INK_SOFT);
        selectAll.setTextSize(12);
        discoveryHeader.addView(selectAll);
        discoveryHeader.setVisibility(View.GONE);
        folderBody.addView(discoveryHeader);
        discoveredList = column();
        folderBody.addView(discoveredList);
        folderBlock.addView(folderBody);
        folderBlock.setVisibility(View.GONE);
        addCard(panel, folderBlock);

        LinearLayout targetCard = cardBox();
        LinearLayout targetHead = row();
        targetHead.addView(text("目标位置", 16, Ui.INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        targetHead.addView(hint("就地分类会把文件移入各来源目录下的“分类”文件夹；"
                + "取消勾选后，可以复制或移动到独立的“目标目录”。"));
        targetCard.addView(targetHead);

        locationCheck = new CheckBox(this);
        locationCheck.setText("就地分类");
        locationCheck.setChecked(draft.inPlace);
        locationCheck.setTextColor(Ui.INK);
        locationCheck.setTextSize(14);
        locationCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        locationCheck.setMinHeight(dp(48));
        locationCheck.setPadding(0, dp(Ui.S1), 0, dp(Ui.S1));
        controls.add(locationCheck);
        locationCheck.setOnCheckedChangeListener((button, checked) -> {
            draft.inPlace = checked;
            if (targetLabel != null) targetLabel.setText(locationLabel());
            if (checked && draft.trash) copyMode.setChecked(true);
            markDraftChanged();
            renderGroups();
            updateReview();
        });
        LinearLayout.LayoutParams locationParams = new LinearLayout.LayoutParams(-1, -2);
        locationParams.setMargins(0, dp(Ui.S2), 0, 0);
        targetCard.addView(locationCheck, locationParams);

        LinearLayout targetRow = row();
        targetRow.setMinimumHeight(dp(56));
        targetRow.setPadding(0, dp(Ui.S1), 0, dp(Ui.S1));
        targetRow.setBackground(Ui.ripple(Ui.RIPPLE_DARK,
                Ui.fill(Color.TRANSPARENT, dp(Ui.R_CONTROL))));
        LinearLayout targetText = column();
        targetText.addView(text("目标目录", 12, Ui.MUTED, false));
        targetLabel = text(locationLabel(), 16, Ui.INK, true);
        targetText.addView(targetLabel);
        targetRow.addView(targetText, new LinearLayout.LayoutParams(0, -2, 1));
        // The folder glyph is a visual hint only: the whole row is the one target, so
        // there is no 48dp button sitting inside a 56dp clickable row.
        ImageView choose = new ImageView(this);
        choose.setImageResource(R.drawable.ic_folder);
        choose.setColorFilter(Ui.INK_SOFT);
        choose.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        chooseParams.setMargins(dp(Ui.S3), 0, dp(Ui.S2), 0);
        targetRow.addView(choose, chooseParams);
        targetChooser = choose;
        targetRow.setClickable(true);
        targetRow.setFocusable(true);
        targetRow.setContentDescription("选择目标目录");
        targetRow.setOnClickListener(v -> {
            if (draft.inPlace) {
                Ui.toastLong(this, "当前是就地分类；取消勾选“就地分类”后可选择独立目标目录");
            } else pick(TARGET);
        });
        controls.add(targetRow);
        LinearLayout.LayoutParams targetParams = new LinearLayout.LayoutParams(-1, -2);
        targetParams.setMargins(0, dp(Ui.S2), 0, 0);
        targetCard.addView(targetRow, targetParams);
        addCard(panel, targetCard);
        return panel;
    }

    private LinearLayout buildStepTwo() {
        LinearLayout panel = column();
        panel.setPadding(0, 0, 0, 0);
        sectionTwo = section(panel, "02", "分类与预览");

        LinearLayout strategyCard = cardBox();
        LinearLayout strategyRow = row();
        strategyRow.setMinimumHeight(dp(56));
        strategyRow.setPadding(0, dp(4), 0, dp(4));
        strategyRow.addView(text("分类策略", 16, Ui.INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        strategy = text(Classification.title(draft.rule), 14, Ui.INK_SOFT, false);
        strategy.setGravity(Gravity.END);
        strategyRow.addView(strategy, new LinearLayout.LayoutParams(-2, -2));
        ImageView strategyChevron = new ImageView(this);
        strategyChevron.setImageResource(R.drawable.ic_chevron);
        strategyChevron.setColorFilter(Ui.FAINT);
        LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(dp(16), dp(16));
        chevronParams.setMargins(dp(Ui.S2), 0, 0, 0);
        strategyRow.addView(strategyChevron, chevronParams);
        strategyRow.setBackground(Ui.ripple(Ui.RIPPLE_DARK, Ui.fill(Color.TRANSPARENT, dp(Ui.R_CHIP))));
        strategyRow.setClickable(true);
        strategyRow.setFocusable(true);
        strategyRow.setContentDescription("分类策略：" + Classification.title(draft.rule));
        strategyRow.setOnClickListener(v -> chooseStrategy());
        controls.add(strategyRow);
        strategyCard.addView(strategyRow);
        addCard(panel, strategyCard);

        groupList = column();
        groupList.setBackground(Ui.outline(Ui.CARD, dp(Ui.R_CARD), Ui.LINE, dp(1)));
        groupList.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams groupParams = new LinearLayout.LayoutParams(-1, -2);
        groupParams.setMargins(0, 0, 0, dp(10));
        panel.addView(groupList, groupParams);

        selection = text("", 12, Ui.INK_SOFT, false);
        selection.setPadding(dp(4), dp(2), dp(4), dp(8));
        panel.addView(selection);
        return panel;
    }

    private LinearLayout buildStepThree() {
        LinearLayout panel = column();
        panel.setPadding(0, 0, 0, 0);
        sectionThree = section(panel, "03", "执行与开始");

        LinearLayout modeCard = cardBox();
        LinearLayout modeHead = row();
        modeHead.addView(text("执行方式", 16, Ui.INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        modeCard.addView(modeHead);

        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(LinearLayout.VERTICAL);
        modes.setPadding(0, dp(6), 0, 0);
        RadioButton copy = new RadioButton(this);
        copyMode = copy;
        copy.setSaveEnabled(false);
        copy.setText("复制，保留原件");
        copy.setTextColor(Ui.INK);
        copy.setTextSize(14);
        copy.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        copy.setId(View.generateViewId());
        copy.setMinHeight(dp(48));
        copy.setGravity(Gravity.CENTER_VERTICAL);
        RadioButton trashRadio = new RadioButton(this);
        trashMode = trashRadio;
        trashRadio.setSaveEnabled(false);
        trashRadio.setText("复制后移入回收站");
        trashRadio.setTextColor(Ui.INK);
        trashRadio.setTextSize(14);
        trashRadio.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        trashRadio.setId(View.generateViewId());
        trashRadio.setMinHeight(dp(48));
        trashRadio.setGravity(Gravity.CENTER_VERTICAL);
        if (!Trash.supported()) trashRadio.setEnabled(false);
        movingMode = new RadioButton(this);
        movingMode.setSaveEnabled(false);
        movingMode.setText("系统移动");
        movingMode.setTextColor(Ui.INK);
        movingMode.setTextSize(14);
        movingMode.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        movingMode.setId(View.generateViewId());
        movingMode.setMinHeight(dp(48));
        movingMode.setGravity(Gravity.CENTER_VERTICAL);
        modes.addView(copy, radioParams());
        modes.addView(trashRadio, radioParams());
        modes.addView(movingMode, radioParams());
        if (draft.move && draft.inPlace) movingMode.setChecked(true);
        else if (draft.trash && Trash.supported()) trashRadio.setChecked(true);
        else { copy.setChecked(true); draft.move = false; draft.trash = false; }
        modes.setOnCheckedChangeListener((group, checked) -> {
            boolean nextMove = checked == movingMode.getId();
            boolean nextTrash = checked == trashRadio.getId();
            if (nextMove && !draft.inPlace) {
                copy.setChecked(true);
                error("系统移动仅支持就地分类；独立目标只允许复制");
                return;
            }
            if (nextTrash && !Trash.supported()) {
                copy.setChecked(true);
                error("Android 11 以下没有可恢复的系统回收站；请使用“复制，保留原件”");
                return;
            }
            draft.move = nextMove;
            draft.trash = nextTrash;
            if (execute != null) execute.setText(Wizard.startLabel(draft.move, draft.trash));
            markDraftChanged();
            updateReview();
            updateWizardFooter();
        });
        controls.add(copy);
        controls.add(trashRadio);
        controls.add(movingMode);
        modeCard.addView(modes);
        addCard(panel, modeCard);

        LinearLayout reviewCard = cardBox();
        reviewCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        reviewCard.addView(text("本次设置摘要", 12, Ui.MUTED, false));
        review = text("", 13, Ui.INK_SOFT, false);
        review.setLineSpacing(dp(4), 1f);
        review.setPadding(0, dp(8), 0, 0);
        reviewCard.addView(review);
        addCard(panel, reviewCard);

        LinearLayout actions = row();
        trial = button("小批量试跑", Ui.SOFT, v -> confirm(false, 3));
        trial.setTextColor(Ui.INK);
        // 主按钮文案与分步模式的“开始…”、执行方式那一行共用一套说法：
        // 开始复制 / 开始文件移动 / 复制后移入回收站（先复制校验，再问是否移入回收站）。
        // 竖排全宽按钮允许换行，fontScale 1.3 时也不会裁切。
        execute = button(Wizard.startLabel(draft.move, draft.trash), Ui.PRIMARY, v -> confirm(draft.move, 0));
        execute.setMinHeight(dp(52));
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.addView(trial, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams execParams = new LinearLayout.LayoutParams(-1, -2);
        execParams.setMargins(0, dp(Ui.S2), 0, 0);
        actions.addView(execute, execParams);
        panel.addView(actions);
        return panel;
    }

    /** Radio rows keep the platform 48dp height and are separated by 8dp of air. */
    private LinearLayout.LayoutParams radioParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(Ui.S1), 0, dp(Ui.S1));
        return params;
    }

    private LinearLayout buildWizardFooter() {
        LinearLayout footer = column();
        footer.setBackground(Ui.outline(Ui.CARD, dp(Ui.R_CARD), Ui.LINE, dp(1)));
        footer.setPadding(dp(Ui.S3), dp(Ui.S2), dp(Ui.S3), dp(Ui.S3));
        wizardBlocker = text("", 12, Ui.INK, true);
        wizardBlocker.setVisibility(View.GONE);
        wizardBlocker.setPadding(dp(4), 0, dp(4), dp(6));
        footer.addView(wizardBlocker);
        LinearLayout actions = row();
        actions.setGravity(Gravity.CENTER_VERTICAL);
        wizardActions = actions;
        prevStep = smallButton("上一步", Ui.SOFT_DARK, v -> goStep(draft.step - 1, true));
        prevStep.setTextColor(Ui.INK);
        actions.addView(prevStep);
        nextStep = button("下一步", Ui.PRIMARY, v -> wizardNext());
        nextStep.setMinHeight(dp(52));
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(0, -2, 1);
        nextParams.setMargins(dp(Ui.S2), 0, 0, 0);
        actions.addView(nextStep, nextParams);
        footer.addView(actions);
        return footer;
    }

    /**
     * 并排两个按钮时，主按钮文案超过 6 个汉字（例如“复制后移入回收站”）必须竖排，
     * 否则在 320dp + fontScale 1.3 下会被折成两行、和“上一步”高低不齐。
     */
    private void layoutWizardActions(boolean stacked) {
        if (wizardActions == null || prevStep == null || nextStep == null) return;
        if ((wizardActions.getOrientation() == LinearLayout.VERTICAL) == stacked) return;
        wizardActions.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        wizardActions.setGravity(stacked ? Gravity.START : Gravity.CENTER_VERTICAL);
        if (stacked) {
            wizardActions.updateViewLayout(prevStep, new LinearLayout.LayoutParams(-2, -2));
            LinearLayout.LayoutParams next = new LinearLayout.LayoutParams(-1, -2);
            next.setMargins(0, dp(Ui.S2), 0, 0);
            wizardActions.updateViewLayout(nextStep, next);
        } else {
            wizardActions.updateViewLayout(prevStep, new LinearLayout.LayoutParams(-2, -2));
            LinearLayout.LayoutParams next = new LinearLayout.LayoutParams(0, -2, 1);
            next.setMargins(dp(Ui.S2), 0, 0, 0);
            wizardActions.updateViewLayout(nextStep, next);
        }
    }

    private void renderSources() {
        sourceList.removeAllViews();
        if (draft.sources.isEmpty()) {
            TextView empty = text("系统相册找不到的文件夹，可在这里添加", 12, Ui.MUTED, false);
            empty.setPadding(0, dp(8), 0, dp(4));
            sourceList.addView(empty);
            return;
        }
        for (String source : draft.sources) {
            LinearLayout row = row();
            row.setMinimumHeight(dp(56));
            row.setPadding(0, dp(6), 0, dp(6));
            LinearLayout texts = column();
            TextView name = text(label(source), 14, Ui.INK, false);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(name);
            boolean authorized;
            try {
                authorized = Storage.readable(getContentResolver(), Storage.id(Uri.parse(source)));
            } catch (Exception e) {
                authorized = false;
            }
            TextView state = text(authorized ? "已授权" : "授权已失效 · 点按重新授权", 12,
                    authorized ? Ui.MUTED : Ui.INK_SOFT, false);
            state.setPadding(0, dp(2), 0, 0);
            texts.addView(state);
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
            // The path can be long; a long press shows it in full instead of truncating silently.
            row.setOnLongClickListener(v -> {
                Ui.toastLong(this, label(source));
                return true;
            });
            if (!authorized) {
                row.setBackground(Ui.ripple(Ui.RIPPLE_DARK, Ui.fill(Color.TRANSPARENT, dp(Ui.R_CHIP))));
                row.setClickable(true);
                row.setOnClickListener(v -> pick(SOURCE));
            }
            ImageButton remove = icon(R.drawable.ic_close, "移除来源目录", v -> {
                if (Session.running) return;
                draft.sources.remove(source);
                markDraftChanged();
                persist();
                invalidateScan();
                renderSources();
                renderGroups();
                updateReview();
            });
            LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(dp(48), dp(48));
            removeParams.setMargins(dp(Ui.S1), 0, 0, 0);
            row.addView(remove, removeParams);
            LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(-1, -2);
            sourceParams.setMargins(0, 0, 0, dp(Ui.S2));
            sourceList.addView(row, sourceParams);
        }
    }

    private void renderGroups() {
        groupList.removeAllViews();
        groupChecks.clear();
        Map<String, List<MediaItem>> groups = new TreeMap<>();
        long photos = 0, videos = 0, unknown = 0, manual = 0;
        for (MediaItem item : Session.items) {
            if (!included(item)) continue;
            if (item.video) videos++; else photos++;
            String folder = Classification.folder(item, draft.rule);
            if (folder == null) { unknown++; continue; }
            if (!item.manualFolder.isEmpty()) manual++;
            groups.computeIfAbsent(folder, key -> new ArrayList<>()).add(item);
        }
        draft.selected.retainAll(groups.keySet());
        for (String group : groups.keySet()) if (!draft.knownGroups.contains(group)) draft.selected.add(group);
        draft.knownGroups.clear();
        draft.knownGroups.addAll(groups.keySet());
        setSummary(photos, videos, unknown);
        if (Session.items.isEmpty()) {
            LinearLayout empty = column();
            empty.setPadding(dp(12), dp(20), dp(12), dp(12));
            empty.addView(text("还没有可以整理的媒体", 14, Ui.INK, true));
            TextView emptyHint = text("先扫描一次，或添加系统相册找不到的文件夹。", 12, Ui.MUTED, false);
            emptyHint.setPadding(0, dp(4), 0, dp(12));
            empty.addView(emptyHint);
            Button bootstrap = quietButton("扫描全部照片 / 视频", v -> startDiscovery());
            empty.addView(bootstrap, new LinearLayout.LayoutParams(-1, -2));
            groupList.addView(empty);
        }
        for (Map.Entry<String, List<MediaItem>> entry : groups.entrySet()) {
            String model = entry.getKey();
            List<MediaItem> items = entry.getValue();
            LinearLayout row = row();
            row.setMinimumHeight(dp(56));
            CheckBox check = new CheckBox(this);
            check.setChecked(draft.selected.contains(model));
            check.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            long v = items.stream().filter(item -> item.video).count();
            long assigned = items.stream().filter(item -> !item.manualFolder.isEmpty()).count();
            check.setText(groupLabel(model) + "\n" + (items.size() - v) + " 张照片 · " + v + " 段视频"
                    + (assigned > 0 ? " · 人工指定 " + assigned : ""));
            check.setTextSize(14);
            check.setTextColor(Ui.INK);
            check.setPadding(0, dp(6), 0, dp(6));
            check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) draft.selected.add(model); else draft.selected.remove(model);
                markDraftChanged();
                updateSelection();
                updateWizardFooter();
            });
            check.setEnabled(!Session.running);
            groupChecks.put(model, check);
            row.addView(check, new LinearLayout.LayoutParams(0, -2, 1));
            ImageButton info = icon(R.drawable.ic_info, "查看文件", view -> {
                if (!Session.running) showFiles(groupLabel(model), items);
            });
            LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(dp(48), dp(48));
            infoParams.setMargins(dp(Ui.S2), 0, 0, 0);
            row.addView(info, infoParams);
            LinearLayout.LayoutParams groupRowParams = new LinearLayout.LayoutParams(-1, -2);
            groupRowParams.setMargins(0, 0, 0, dp(Ui.S2));
            groupList.addView(row, groupRowParams);
        }
        pendingButton = button("待确认 " + unknown + " 个 · 暂不处理", Ui.SOFT_DARK, v -> {
            if (Session.running) return;
            List<MediaItem> items = new ArrayList<>();
            for (MediaItem item : Session.items) if (included(item) && Classification.folder(item, draft.rule) == null) items.add(item);
            showPending(items);
        });
        pendingButton.setTextColor(Ui.MUTED);
        pendingButton.setVisibility(unknown == 0 ? View.GONE : View.VISIBLE);
        groupList.addView(pendingButton);
        resetButton = button("撤销人工指定 · " + manual + " 个", Ui.SOFT_DARK, v -> {
            if (Session.running) return;
            new AlertDialog.Builder(this).setTitle("撤销全部人工指定？")
                    .setMessage("原始参数不变，文件将重新按当前策略分类。")
                    .setNegativeButton("取消", null).setPositiveButton("撤销", (dialog, which) ->
                            assignFolders(Session.items, "")).show();
        });
        resetButton.setTextColor(Ui.MUTED);
        resetButton.setVisibility(manual == 0 ? View.GONE : View.VISIBLE);
        groupList.addView(resetButton);
        updateAuxiliary(unknown, manual);
        updateSelection();
        updateReview();
        updateWizardFooter();
        if (draft.active) saveDraftState();
    }

    private void updateSelection() {
        if (selection == null) return;
        long bytes = 0;
        int count = 0;
        for (MediaItem item : Session.items) {
            if (!included(item)) continue;
            String folder = Classification.folder(item, draft.rule);
            if (folder != null && draft.selected.contains(folder)) { count++; bytes += Math.max(0, item.size); }
        }
        selection.setText("已选 " + draft.selected.size() + " 个目录 · " + count + " 个文件 · " + size(bytes));
        updateReview();
    }

    private boolean included(MediaItem item) {
        if (draft.excludedFolders.contains(item.parentId)) return false;
        return draft.inPlace || draft.targetParentId.isEmpty()
                || !MediaPaths.contains(draft.targetParentId, item.parentId);
    }

    private void renderDiscovered() {
        discoveredList.removeAllViews();
        discoveredChecks.clear();
        Map<String, List<MediaItem>> parents = new TreeMap<>();
        for (MediaItem item : Session.items) {
            if (!item.parentId.isEmpty()) parents.computeIfAbsent(item.parentId, key -> new ArrayList<>()).add(item);
        }
        int authorized = 0;
        Storage.Grants grants = Storage.grants(getContentResolver());
        for (Map.Entry<String, List<MediaItem>> entry : parents.entrySet()) {
            String parent = entry.getKey();
            if (draft.knownFolders.add(parent) && MediaPaths.managed(parent)) draft.excludedFolders.add(parent);
            LinearLayout row = row();
            row.setMinimumHeight(dp(56));
            CheckBox check = new CheckBox(this);
            long bytes = entry.getValue().stream().mapToLong(item -> Math.max(0, item.size)).sum();
            boolean locked = MediaPaths.ungrantable(parent);
            boolean writable;
            try { grants.document(parent); writable = true; }
            catch (java.io.IOException e) { writable = false; }
            if (writable) authorized++;
            boolean isTarget = !draft.inPlace && !draft.targetParentId.isEmpty()
                    && MediaPaths.contains(draft.targetParentId, parent);
            check.setText(MediaPaths.display(parent) + "\n" + entry.getValue().size() + " 个文件 · " + size(bytes)
                    + (locked ? " · 系统不允许授权"
                    : writable ? " · 已授权" : " · 未授权 · 点右侧图标授权")
                    + (isTarget ? " · 当前目标目录（不参与整理）" : ""));
            check.setTextSize(13);
            check.setTextColor(Ui.INK);
            check.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            check.setPadding(0, dp(4), 0, dp(4));
            check.setMinHeight(dp(48));
            row.setPadding(0, dp(2), 0, dp(2));
            check.setChecked(!isTarget && !draft.excludedFolders.contains(parent));
            check.setOnCheckedChangeListener((button, checked) -> {
                if (syncing) return;
                if (checked) draft.excludedFolders.remove(parent); else draft.excludedFolders.add(parent);
                persistFolders();
                markDraftChanged();
                updateFolderBlock();
                scheduleGroupRefresh();
            });
            check.setTag(isTarget ? "target-folder" : null);
            if (isTarget) check.setEnabled(false);
            discoveredChecks.put(parent, check);
            row.addView(check, new LinearLayout.LayoutParams(0, -2, 1));
            // 系统不会给这个目录授权，放一个点了也只会打开灰色“使用此文件夹”的入口没有意义。
            if (!locked) {
                ImageButton grant = icon(R.drawable.ic_folder, "授权来源目录 " + MediaPaths.display(parent), v -> {
                    if (Session.running) return;
                    Intent intent = directoryIntent();
                    intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
                            Storage.LOCAL, parent));
                    startActivityForResult(intent, SOURCE);
                });
                LinearLayout.LayoutParams grantParams = new LinearLayout.LayoutParams(dp(48), dp(48));
                grantParams.setMargins(dp(Ui.S2), 0, 0, 0);
                row.addView(grant, grantParams);
            }
            enableChildren(row, !Session.running);
            LinearLayout.LayoutParams discoveredParams = new LinearLayout.LayoutParams(-1, -2);
            discoveredParams.setMargins(0, 0, 0, dp(Ui.S2));
            discoveredList.addView(row, discoveredParams);
        }
        authorizedFolders = authorized;
        persistFolders();
        updateFolderBlock();
    }

    private void persistFolders() {
        if (store != null) store.saveFolders(draft);
    }

    private boolean isTargetFolder(String parent) {
        return !draft.inPlace && !draft.targetParentId.isEmpty() && MediaPaths.contains(draft.targetParentId, parent);
    }

    private void updateDiscoveryHeader() {
        if (selectAll == null || discoveryHeader == null) return;
        int total = 0, selectedCount = 0;
        for (String parent : discoveredChecks.keySet()) {
            if (isTargetFolder(parent)) continue;
            total++;
            if (!draft.excludedFolders.contains(parent)) selectedCount++;
        }
        discoveryHeader.setVisibility(total == 0 ? View.GONE : View.VISIBLE);
        selectAll.setText(total > 0 && selectedCount == total ? "取消全选" : "全选");
        selectAll.setEnabled(total > 0 && !Session.running);
    }

    /**
     * The folder list is the only section that can grow without bound. It starts
     * folded and shows counts instead, so the bottom actions stay reachable.
     */
    private void updateFolderBlock() {
        if (folderBlock == null) return;
        int total = 0, selectedCount = 0, locked = 0;
        for (String parent : discoveredChecks.keySet()) {
            if (isTargetFolder(parent)) continue;
            total++;
            if (MediaPaths.ungrantable(parent)) locked++;
            if (!draft.excludedFolders.contains(parent)) selectedCount++;
        }
        folderBlock.setVisibility(total == 0 ? View.GONE : View.VISIBLE);
        if (total == 0) return;
        // 系统不会授权的目录不计入“已授权 x/y”的分母：那个分数只会永远停在未满；
        // 行内“系统不允许授权”已经说明了原因，摘要保持一行。
        folderSummary.setText("发现目录 · 已授权 " + authorizedFolders + "/" + (total - locked)
                + " · 已选 " + selectedCount);
        folderToggle.setText(draft.foldersCollapsed ? "展开" : "收起");
        folderBody.setVisibility(draft.foldersCollapsed ? View.GONE : View.VISIBLE);
        updateDiscoveryHeader();
    }

    private void toggleFolders() {
        draft.foldersCollapsed = !draft.foldersCollapsed;
        store.saveCollapsed(draft);
        updateFolderBlock();
    }

    private void toggleAllDiscovered() {
        if (Session.running || discoveredChecks.isEmpty()) return;
        List<String> parents = new ArrayList<>();
        for (String parent : discoveredChecks.keySet()) if (!isTargetFolder(parent)) parents.add(parent);
        if (parents.isEmpty()) return;
        boolean all = true;
        for (String parent : parents) if (draft.excludedFolders.contains(parent)) { all = false; break; }
        syncing = true;
        try {
            if (all) draft.excludedFolders.addAll(parents); else draft.excludedFolders.removeAll(parents);
            for (String parent : parents) discoveredChecks.get(parent).setChecked(!draft.excludedFolders.contains(parent));
        } finally { syncing = false; }
        persistFolders();
        markDraftChanged();
        updateFolderBlock();
        scheduleGroupRefresh();
    }

    private void scheduleGroupRefresh() {
        handler.removeCallbacks(groupRefresh);
        handler.postDelayed(groupRefresh, 80);
    }

    /** 只更新分类计数文字，不重建列表，避免来源目录多时勾选卡顿。 */
    private void refreshGroupCounts() {
        if (Session.running) return;
        Map<String, long[]> stats = new TreeMap<>();
        long photos = 0, videos = 0, unknown = 0, manual = 0;
        for (MediaItem item : Session.items) {
            if (!included(item)) continue;
            if (item.video) videos++; else photos++;
            String folder = Classification.folder(item, draft.rule);
            if (folder == null) { unknown++; continue; }
            if (!item.manualFolder.isEmpty()) manual++;
            long[] counts = stats.computeIfAbsent(folder, key -> new long[3]);
            if (item.video) counts[1]++; else counts[0]++;
            if (!item.manualFolder.isEmpty()) counts[2]++;
        }
        if (!stats.keySet().equals(groupChecks.keySet())) { renderGroups(); return; }
        for (Map.Entry<String, CheckBox> entry : groupChecks.entrySet()) {
            long[] counts = stats.get(entry.getKey());
            entry.getValue().setText(groupLabel(entry.getKey()) + "\n" + counts[0] + " 张照片 · " + counts[1] + " 段视频"
                    + (counts[2] > 0 ? " · 人工指定 " + counts[2] : ""));
        }
        setSummary(photos, videos, unknown);
        updateAuxiliary(unknown, manual);
        updateSelection();
    }

    private void updateAuxiliary(long unknown, long manual) {
        if (pendingButton != null) {
            pendingButton.setText("待确认 " + unknown + " 个 · 暂不处理");
            pendingButton.setVisibility(unknown > 0 ? View.VISIBLE : View.GONE);
        }
        if (resetButton != null) {
            resetButton.setText("撤销人工指定 · " + manual + " 个");
            resetButton.setVisibility(manual > 0 ? View.VISIBLE : View.GONE);
        }
    }

    private void showFiles(String title, List<MediaItem> items) {
        showFiles(title, items, 0);
    }

    /**
     * One card per file with separate "原始" and "目标" lines. The old list
     * collapsed all three lines into one sentence, which made a 100-file batch
     * impossible to audit.
     */
    private void showFiles(String title, List<MediaItem> items, int start) {
        if (items.isEmpty()) { error("没有可查看的文件"); return; }
        int page = 30;
        int end = Math.min(start + page, items.size());
        Dialog dialog = new Dialog(this);
        LinearLayout body = column();
        body.setPadding(dp(16), dp(24), dp(16), dp(10));
        body.setBackgroundColor(Ui.BG);

        LinearLayout heading = row();
        heading.addView(text(title, 18, Ui.INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView count = text((start + 1) + "-" + end + " / " + items.size(), 13, Ui.MUTED, false);
        heading.addView(count);
        body.addView(heading);
        body.addView(text("当前方式：" + modeLabel() + " · 每页 " + page + " 个", 12, Ui.MUTED, false));
        space(body, 8);

        ScrollView scroll = new ScrollView(this);
        LinearLayout cards = column();
        for (int i = start; i < end; i++) {
            MediaItem item = items.get(i);
            LinearLayout card = column();
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            GradientDrawable background = new GradientDrawable();
            background.setColor(Ui.CARD);
            background.setCornerRadius(dp(12));
            background.setStroke(dp(1), Ui.LINE);
            card.setBackground(background);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
            cardParams.setMargins(0, 0, 0, dp(8));

            card.addView(text((i + 1) + ". " + item.name, 14, Ui.INK, true));
            String kind = item.video ? "视频" : "照片";
            card.addView(text(size(Math.max(0, item.size)) + " · " + kind
                    + (item.width > 0 ? " · " + item.width + "×" + item.height : "")
                    + (item.capturedAt.isEmpty() ? " · 拍摄时间未知" : " · " + item.capturedAt.replace('T', ' '))
                    + (item.model == null ? "" : " · " + item.model), 12, Ui.MUTED, false));
            TextView from = text("原始： " + sourcePath(item), 13, Ui.INK, false);
            from.setTextIsSelectable(true);
            from.setPadding(0, dp(4), 0, 0);
            card.addView(from);
            String folder = Classification.folder(item, draft.rule);
            TextView to = text("目标： " + (folder == null
                    ? "待确认（" + Classification.missing(item, draft.rule) + "），保留原处"
                    : plannedPath(item)), 13, folder == null ? Ui.INK : Ui.PRIMARY, false);
            to.setTextIsSelectable(true);
            card.addView(to);
            if (!item.issue.isEmpty()) card.addView(text(item.issue, 12, Ui.INK, false));
            card.setOnClickListener(v -> showDetails(item));
            cards.addView(card, cardParams);
        }
        scroll.addView(cards);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout pager = row();
        pager.setGravity(Gravity.END);
        pager.setPadding(0, dp(Ui.S2), 0, 0);
        Ui.Action previous = smallButton("上一页", Ui.SOFT_DARK,
                v -> { dialog.dismiss(); showFiles(title, items, Math.max(0, start - page)); });
        previous.setTextColor(Ui.INK);
        previous.setEnabled(start > 0);
        pager.addView(previous);
        Ui.Action next = smallButton("下一页", Ui.SOFT_DARK,
                v -> { dialog.dismiss(); showFiles(title, items, end); });
        next.setTextColor(Ui.INK);
        next.setEnabled(end < items.size());
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(-2, -2);
        nextParams.setMargins(dp(Ui.S2), 0, 0, 0);
        pager.addView(next, nextParams);
        Ui.Action close = smallButton("关闭", Ui.PRIMARY, v -> dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-2, -2);
        closeParams.setMargins(dp(Ui.S2), 0, 0, 0);
        pager.addView(close, closeParams);
        body.addView(pager);

        dialog.setContentView(body);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Ui.BG));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (int) (getResources().getDisplayMetrics().heightPixels * 0.9));
        }
        dialog.show();
    }

    private String sourcePath(MediaItem item) {
        return MediaPaths.display(item.parentId) + "/" + item.name;
    }

    private String plannedPath(MediaItem item) {
        String folder = Classification.folder(item, draft.rule);
        if (folder == null) return "待确认，保留原处";
        String base;
        if (draft.inPlace) base = MediaPaths.display(item.parentId) + "/" + MediaPaths.ARCHIVE;
        else if (draft.target.isEmpty()) return "目标目录尚未选择";
        else base = targetDisplay();
        return base + (folder.isEmpty() ? "" : "/" + folder) + "/" + item.name;
    }

    private String locationLabel() {
        if (draft.inPlace) return "各来源目录 / " + MediaPaths.ARCHIVE
                + (Classification.FLAT.equals(draft.rule) ? "（不建子目录）" : "");
        return draft.target.isEmpty() ? "未选择" : targetDisplay();
    }

    private String targetDisplay() {
        if (draft.target.isEmpty()) return "未选择";
        try { return MediaPaths.display(Storage.id(Uri.parse(draft.target))); }
        catch (Exception e) { return label(draft.target); }
    }

    private String modeLabel() {
        return draft.move ? "系统移动" : draft.trash ? "复制后移入回收站" : "复制（保留原件）";
    }

    private void showDetails(MediaItem item) {
        String details = "原始：" + sourcePath(item)
                + (item.width > 0 ? "\n尺寸：" + item.width + "×" + item.height : "")
                + (item.duration > 0 ? "\n时长：" + duration(item.duration) : "")
                + "\n\n设备：" + (item.model == null ? "未知或冲突" : item.model)
                + "\n拍摄时间：" + (item.capturedAt.isEmpty() ? "未知或冲突" : item.capturedAt.replace('T', ' '))
                + (!item.capturedAt.isEmpty() && item.capturedAt.length() == 19 ? "（未记录时区）" : "")
                + "\n字段来源：" + (item.metadataSource.isEmpty() ? "无" : item.metadataSource)
                + "\n\n" + item.parameters + (item.issue.isEmpty() ? "" : "\n" + item.issue)
                + "\n\n当前目标：" + plannedPath(item)
                + "\n当前方式：" + modeLabel()
                + (item.manualFolder.isEmpty() ? "" : "\n人工指定，未修改原始参数");
        TextView content = text(details, 14, Ui.INK, false);
        content.setTextIsSelectable(true);
        content.setPadding(dp(20), dp(12), dp(20), dp(12));
        LinearLayout body = column();
        LinearLayout header = row();
        TextView heading = text("原始文件与参数", 14, Ui.INK, true);
        heading.setPadding(dp(20), 0, 0, 0);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(icon(R.drawable.ic_photo, "查看原文件", v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.uri), item.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            } catch (RuntimeException e) { error("无法打开原文件，请检查本机是否有支持此格式的应用"); }
        }));
        body.addView(header);
        body.addView(content);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(item.name).setView(scroll)
                .setNegativeButton("关闭", null)
                .setPositiveButton("指定目录", (d, w) -> chooseFolder(java.util.Collections.singletonList(item)));
        if (!item.manualFolder.isEmpty()) dialog.setNeutralButton("撤销指定",
                (d, w) -> assignFolders(java.util.Collections.singletonList(item), ""));
        dialog.show();
    }

    private void showPending(List<MediaItem> items) {
        new AlertDialog.Builder(this).setTitle("待确认 · " + items.size())
                .setItems(new String[]{"查看文件与原始参数", "选择部分文件指定目录", "整批指定目录"}, (dialog, which) -> {
                    if (Session.running) return;
                    if (which == 0) showFiles("待确认", items);
                    else if (which == 1) choosePendingFiles(items);
                    else chooseFolder(items);
                }).setNegativeButton("保留原处", null).show();
    }

    private void choosePendingFiles(List<MediaItem> items) {
        String[] labels = new String[items.size()];
        boolean[] checked = new boolean[items.size()];
        for (int i = 0; i < items.size(); i++) {
            MediaItem item = items.get(i);
            labels[i] = item.path + "\n" + Classification.missing(item, draft.rule)
                    + (item.issue.isEmpty() ? "" : "\n" + item.issue);
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("待确认 · " + items.size())
                .setMultiChoiceItems(labels, checked, (d, index, value) -> checked[index] = value)
                .setNegativeButton("保留原处", null)
                .setPositiveButton("指定所选目录", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<MediaItem> chosen = new ArrayList<>();
            for (int i = 0; i < checked.length; i++) if (checked[i]) chosen.add(items.get(i));
            if (chosen.isEmpty()) { Ui.toast(this, "尚未选择文件"); return; }
            dialog.dismiss();
            chooseFolder(chosen);
        }));
        dialog.show();
    }

    private void chooseFolder(List<MediaItem> items) {
        if (Session.running || items.isEmpty()) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("例如：家庭相册/待整理");
        if (items.size() == 1) input.setText(items.get(0).manualFolder);
        LinearLayout content = column();
        content.setPadding(dp(20), 0, dp(20), 0);
        content.addView(text(items.size() + " 个文件 · 目标：" + locationLabel(), 14, Ui.MUTED, false));
        content.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("人工指定归档目录")
                .setView(content).setNegativeButton("取消", null).setPositiveButton("应用", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String folder = Classification.manualPath(input.getText().toString());
                if (assignFolders(items, folder)) dialog.dismiss();
            } catch (IllegalArgumentException e) { input.setError(e.getMessage()); }
        }));
        dialog.show();
    }

    private boolean assignFolders(List<MediaItem> chosen, String folder) {
        if (Session.running) return false;
        Set<String> uris = new HashSet<>();
        for (MediaItem item : chosen) uris.add(item.uri);
        List<MediaItem> updated = new ArrayList<>();
        for (MediaItem item : Session.items) updated.add(uris.contains(item.uri) ? item.withFolder(folder) : item);
        try {
            Session.saveScan(this, updated);
            markDraftChanged();
            renderGroups();
            updateReview();
            return true;
        } catch (Exception e) { error("无法保存人工分类：" + e.getMessage()); return false; }
    }

    private void chooseStrategy() {
        if (Session.running) return;
        int current = java.util.Arrays.asList(Classification.RULES).indexOf(draft.rule);
        new AlertDialog.Builder(this).setTitle("分类策略")
                .setSingleChoiceItems(Classification.TITLES, current < 0 ? Classification.RULES.length : current,
                        (dialog, which) -> {
                            dialog.dismiss();
                            if (which >= Classification.RULES.length) customStrategy();
                            else applyRule(Classification.RULES[which]);
                        }).setNegativeButton("取消", null).show();
    }

    private void customStrategy() {
        LinearLayout content = column();
        content.setPadding(dp(20), 0, dp(20), 0);
        String[] choices = new String[Classification.FIELD_TITLES.length + 1];
        choices[0] = "不使用";
        System.arraycopy(Classification.FIELD_TITLES, 0, choices, 1, Classification.FIELD_TITLES.length);
        String[] existing = draft.customRule.split("/");
        List<Spinner> layers = new ArrayList<>();
        TextView preview = text("", 14, Ui.MUTED, false);
        for (int i = 0; i < 5; i++) {
            LinearLayout row = row();
            row.addView(text("第 " + (i + 1) + " 层", 14, Ui.INK, false));
            Spinner spinner = new Spinner(this);
            spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices));
            spinner.setSelection(i < existing.length ? java.util.Arrays.asList(Classification.FIELDS).indexOf(existing[i]) + 1 : 0);
            row.addView(spinner, new LinearLayout.LayoutParams(0, dp(48), 1));
            layers.add(spinner);
            content.addView(row);
        }
        content.addView(preview);
        for (Spinner spinner : layers) spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String candidate = ruleFrom(layers);
                preview.setText(Classification.valid(candidate) ? "目录：" + Classification.title(candidate) : "请选择设备或拍摄时间，层级不可重复");
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("自定义目录层级")
                .setView(scroll).setNegativeButton("取消", null).setPositiveButton("应用", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String candidate = ruleFrom(layers);
            if (!Classification.valid(candidate)) { preview.setText("请选择设备或拍摄时间，层级不可重复"); return; }
            draft.customRule = candidate;
            prefs.edit().putString("customRule", draft.customRule).apply();
            applyRule(candidate);
            dialog.dismiss();
        }));
        dialog.show();
    }

    private String ruleFrom(List<Spinner> layers) {
        List<String> fields = new ArrayList<>();
        for (Spinner spinner : layers) {
            int index = spinner.getSelectedItemPosition();
            if (index > 0) fields.add(Classification.FIELDS[index - 1]);
        }
        return String.join("/", fields);
    }

    private void applyRule(String value) {
        if (Session.running) return;
        draft.rule = value;
        markDraftChanged();
        prefs.edit().putString("rule", draft.rule).apply();
        draft.selected.clear();
        draft.knownGroups.clear();
        strategy.setText(Classification.title(draft.rule));
        if (targetLabel != null) targetLabel.setText(locationLabel());
        renderGroups();
        updateReview();
    }

    private void enableChildren(View view, boolean enabled) {
        if ("target-folder".equals(view.getTag())) {
            view.setEnabled(false);
            return;
        }
        if (view.isEnabled() != enabled) view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) enableChildren(group.getChildAt(i), enabled);
        }
    }

    private void pick(int request) {
        if (Session.running) return;
        startActivityForResult(directoryIntent(), request);
    }

    private Intent directoryIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == AUTH) {
            if (result != RESULT_OK || data == null || data.getData() == null) { authTarget = ""; authQueue.clear(); return; }
            authorizeGranted(data.getData(), data);
            return;
        }
        if (request == TRASH) {
            handleTrashResult(result);
            return;
        }
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == BROAD) {
            authorizeBroadGranted(data.getData(), data);
            return;
        }
        if (request == DIRECT) {
            startDirect(data.getData(), data);
            return;
        }
        Uri uri = data.getData();
        if (request == LogDialog.EXPORT_REQUEST) { logs.export(uri); return; }
        try {
            String pickedId = Storage.id(uri);
            if (Session.running) throw new java.io.IOException("请等待当前任务完成");
            // Re-picking a folder that is already a source only renews its grant; it must
            // not fail as a duplicate, because that is the only way to recover a dropped grant.
            boolean known = false;
            if (request == SOURCE) {
                for (String source : draft.sources) {
                    if (pickedId.equals(Storage.id(Uri.parse(source)))) known = true;
                }
            }
            if (!known && !draft.inPlace && request == TARGET) {
                String targetId = Storage.id(uri);
                for (String source : draft.sources) {
                    if (targetId.equals(Storage.id(Uri.parse(source)))) {
                        throw new java.io.IOException("目标目录不能就是来源目录；请换一个文件夹");
                    }
                }
            } else if (!known) {
                if (!draft.inPlace && !draft.target.isEmpty()) ensureSeparate(uri, Uri.parse(draft.target));
                for (String source : draft.sources) {
                    if (Naming.overlaps(Storage.id(uri), Storage.id(Uri.parse(source)))) {
                        throw new java.io.IOException("该目录与已有来源重复或互相包含");
                    }
                }
            }
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) throw new java.io.IOException("未获得读取授权");
            if (request == TARGET && (flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
                throw new java.io.IOException("目标目录不可写");
            }
            if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } else {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            if (request == SOURCE) {
                if (!known) draft.sources.add(uri.toString());
                markDraftChanged();
                invalidateScan();
                renderSources();
                renderGroups();
                if (known) Ui.toast(this, "已重新授权，可以再次扫描");
            } else {
                draft.target = uri.toString();
                draft.targetParentId = Storage.id(uri);
                targetLabel.setText(targetDisplay());
                markDraftChanged();
                renderGroups();
            }
            copyMode.setChecked(true);
            persist();
            updateReview();
            updateWizardFooter();
            updateHub();
        } catch (Exception e) { error(e.getMessage()); }
    }

    private void ensureSeparate(Uri source, Uri destination) throws java.io.IOException {
        if (Naming.overlaps(Storage.id(source), Storage.id(destination))) {
            throw new java.io.IOException("来源和目标不能相同或互相包含，请选择独立目录");
        }
    }

    private void startScan() {
        if (Session.running) return;
        copyMode.setChecked(true);
        scannedAll = false;
        prefs.edit().putBoolean("scannedAll", false).apply();
        // Keep the user's category choices: the incremental scan only adds new
        // groups, and re-checking everything after every scan was unnecessary.
        launch(new Intent(this, SortService.class).setAction("scan").putStringArrayListExtra("sources", draft.sources));
    }

    private void startDiscovery() {
        if (Session.running) return;
        if (!MediaDiscovery.accessible(this)) { requestMediaPermissions(); return; }
        scannedAll = true;
        prefs.edit().putBoolean("scannedAll", true).apply();
        launch(new Intent(this, SortService.class).setAction("scan_all"));
    }

    /** Re-read every file's EXIF/QuickTime data once; the normal path reuses the cache. */
    private void rebuildDiscovery() {
        if (Session.running) { Ui.toast(this, "任务进行中，请稍候"); return; }
        if (!MediaDiscovery.accessible(this)) { requestMediaPermissions(); return; }
        scannedAll = true;
        prefs.edit().putBoolean("scannedAll", true).apply();
        launch(new Intent(this, SortService.class).setAction("scan_all").putExtra("rebuild", true));
    }

    private void confirmRebuild() {
        new AlertDialog.Builder(this)
                .setTitle("重建媒体索引")
                .setMessage("重新读取每个文件的拍摄设备、拍摄时间等参数。\n\n"
                        + "平时不需要这样做：查找时只读取新增或有变化的文件，其余直接复用缓存。"
                        + "只有怀疑设备名、拍摄日期读错，或刚升级过 App 时才需要重建。\n\n"
                        + "文件很多时会花较长时间；不会修改或删除任何照片、视频。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始重建", (dialog, which) -> rebuildDiscovery())
                .show();
    }

    /** Triggered by MediaStore notifications while this page is visible. */
    private void startMediaSync() {
        if (Session.running) return;
        if (!MediaDiscovery.accessible(this)) return;
        handler.removeCallbacks(mediaCheck);
        try {
            // The Activity is in the foreground, so a normal started service is
            // enough; quiet mode avoids a notification for every camera save.
            startService(new Intent(this, SortService.class)
                    .setAction("scan_all").putExtra("quiet", true));
        } catch (RuntimeException ignored) {
            // The next resume or explicit scan will retry.
        }
    }

    private void scheduleMediaCheck(long delayMs) {
        handler.removeCallbacks(mediaCheck);
        handler.postDelayed(mediaCheck, delayMs);
    }

    private void registerMediaObserver() {
        if (mediaObserverRegistered) return;
        ContentResolver resolver = getContentResolver();
        try {
            resolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, mediaObserver);
            resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver);
            resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver);
            mediaObserverRegistered = true;
        } catch (RuntimeException ignored) {
            // Observation is an optimisation; explicit scanning still works.
        }
    }

    private void unregisterMediaObserver() {
        if (!mediaObserverRegistered) return;
        try { getContentResolver().unregisterContentObserver(mediaObserver); }
        catch (RuntimeException ignored) { }
        mediaObserverRegistered = false;
    }

    private void updateMediaScope() {
        if (mediaScope == null) return;
        String next = !MediaDiscovery.accessible(this) ? "none"
                : !MediaDiscovery.full(this) ? "partial" : "full";
        if (next.equals(mediaScopeState)) return;
        mediaScopeState = next;
        if ("none".equals(next)) {
            styleScopeWarning("尚未授权读取照片/视频。点这里允许全部，才能看到手机里的所有照片和视频。");
        } else if ("partial".equals(next)) {
            styleScopeWarning("当前只允许访问部分照片/视频，Download、Movies 等目录可能完全看不到。点这里改为“允许全部”。");
        } else {
            mediaScope.setVisibility(View.GONE);
        }
    }

    /** Permission gaps are the one state that must stay visible: tinted accent surface, no border. */
    private void styleScopeWarning(String message) {
        mediaScope.setVisibility(View.VISIBLE);
        mediaScope.setText("!  " + message);
        mediaScope.setTextColor(Ui.ON_ACCENT_SOFT);
        mediaScope.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mediaScope.setLineSpacing(0f, 1.4f);
        mediaScope.setPadding(dp(12), dp(10), dp(12), dp(10));
        mediaScope.setBackground(Ui.fill(Ui.ACCENT_SOFT, dp(Ui.R_CHIP)));
    }

    /** Opens a gallery-style list over every MediaStore directory, including Download. */
    private void openAllMedia() {
        if (Session.running) {
            Ui.toast(this, "正在扫描或整理，请稍候");
            return;
        }
        if (!MediaDiscovery.accessible(this)) {
            libraryAfterScan = true;
            requestMediaPermissions();
            return;
        }
        if (!MediaDiscovery.full(this)) {
            showPartialAccessDialog();
            return;
        }
        // Opening the gallery is a read, not a request to rebuild the library. If the
        // index is still current, show it immediately; otherwise run the incremental
        // sync (which only opens new/changed files) and then open the gallery.
        if (scannedAll && !Session.items.isEmpty() && MediaCache.fresh(this)) {
            showLibrary(false);
            return;
        }
        libraryAfterScan = true;
        libraryBaseline = Session.revision;
        startDiscovery();
    }

    private void showPartialAccessDialog() {
        new AlertDialog.Builder(this)
                .setTitle("只允许访问部分照片/视频")
                .setMessage("Android 14 起，系统权限里的“选择照片和视频”只会让 App 看到你勾选的项目，"
                        + "Download、Movies 等目录可能完全不会出现。\n\n"
                        + "要像系统相册一样管理全部媒体，请在接下来的权限窗口选择“允许全部”。")
                .setPositiveButton("去允许全部", (dialog, which) -> {
                    libraryAfterScan = true;
                    libraryBaseline = Session.revision;
                    requestMediaPermissions();
                })
                .setNegativeButton("先看已选", (dialog, which) -> showLibrary(false))
                .show();
    }

    private void requestMediaPermissions() {
        if (Session.running) return;
        if (Build.VERSION.SDK_INT >= 34) {
            requestPermissions(new String[] {Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}, 21);
        } else if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[] {Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO}, 21);
        } else requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, 21);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 21) {
            if (!MediaDiscovery.accessible(this)) {
                libraryAfterScan = false;
                error("未获得照片或视频读取权限；仍可通过添加来源目录扫描");
                return;
            }
            if (libraryAfterScan && !MediaDiscovery.full(this)) {
                libraryAfterScan = false;
                showPartialAccessDialog();
                return;
            }
            if (libraryAfterScan) libraryBaseline = Session.revision;
            startDiscovery();
        }
    }

    private void showLibrary(boolean recallOnly) {
        if (Session.running) return;
        if (Session.items.isEmpty()) {
            error("还没有媒体清单。请先在“文件移动分类”里点“扫描全部照片 / 视频”，授权读取后再浏览、搜索或找回。");
            return;
        }
        LibraryDialog.show(this, Session.items, recallOnly, new LibraryDialog.Host() {
            @Override public void copyToFolder(List<MediaItem> items) { MainActivity.this.copyToFolder(items); }
            @Override public void recallToCamera(List<MediaItem> items) { confirmRecall(items); }
            @Override public void trash(List<MediaItem> items) { MainActivity.this.startTrash(items, "媒体库"); }
        });
    }

    private void copyToFolder(List<MediaItem> items) {
        if (Session.running || items.isEmpty()) return;
        directUris.clear();
        for (MediaItem item : items) directUris.add(item.uri);
        pick(DIRECT);
    }

    private void startDirect(Uri uri, Intent data) {
        if (Session.running) return;
        if (directUris.isEmpty()) { error("没有选中的文件，请重新选择"); return; }
        try {
            String targetId = Storage.id(uri);
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) throw new java.io.IOException("未获得读取授权");
            if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) throw new java.io.IOException("目标目录不可写");
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            Set<String> wanted = new HashSet<>(directUris);
            List<MediaItem> plan = new ArrayList<>();
            long bytes = 0;
            for (MediaItem item : Session.items) {
                if (!wanted.contains(item.uri)) continue;
                if (targetId.equals(item.parentId)) {
                    throw new java.io.IOException("目标目录就是这些文件的原目录；直接放入会在同一目录生成副本，请换一个文件夹");
                }
                plan.add(item);
                bytes += Math.max(0, item.size);
            }
            if (plan.isEmpty()) throw new java.io.IOException("选中的文件已不在当前扫描结果中，请重新查找");
            String targetName = MediaPaths.display(targetId);
            new AlertDialog.Builder(this)
                    .setTitle("直接复制到 " + targetName)
                    .setMessage("将 " + plan.size() + " 个文件（" + size(bytes) + "）直接放入目标文件夹根目录，"
                            + "不再建立设备/日期子目录。\n\n每个文件都会 SHA-256 校验；只有全部成功，"
                            + "才会询问是否把原件移入系统回收站。可先从回收站恢复。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("开始复制", (dialog, which) -> {
                        trashPrompted = false;
                        Intent intent = new Intent(this, SortService.class).setAction("copy_direct")
                                .putExtra("target", uri.toString()).putExtra("trash", true)
                                .putStringArrayListExtra("uris", new ArrayList<>(directUris));
                        directUris.clear();
                        launch(intent);
                    }).show();
        } catch (Exception e) {
            error("无法使用该目标目录：" + e.getMessage());
        }
    }

    private void confirmRecall(List<MediaItem> items) {
        if (Session.running) return;
        if (android.os.Build.VERSION.SDK_INT < 29) {
            error("Android 10 以下无法直接写入系统媒体库；请用“复制到其他文件夹”并授权 DCIM/Camera。");
            return;
        }
        List<MediaItem> plan = new ArrayList<>();
        for (MediaItem item : items) if (!MediaPaths.inCamera(item.parentId)) plan.add(item);
        if (plan.isEmpty()) { error("所选文件已经在 DCIM/Camera 中"); return; }
        long bytes = 0;
        for (MediaItem item : plan) bytes += Math.max(0, item.size);
        new AlertDialog.Builder(this)
                .setTitle("放回 DCIM/Camera")
                .setMessage("将 " + plan.size() + " 个文件（" + size(bytes) + "）复制回 内部存储/DCIM/Camera。\n\n"
                        + "目标经过 SHA-256 校验后才会显示在相册；只有全部成功，才会询问是否把原件移入系统回收站。"
                        + "未完成的文件保留原处，不会移动或删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始", (dialog, which) -> {
                    ArrayList<String> uris = new ArrayList<>();
                    for (MediaItem item : plan) uris.add(item.uri);
                    trashPrompted = false;
                    launch(new Intent(this, SortService.class).setAction("recall_camera")
                            .putStringArrayListExtra("uris", uris));
                }).show();
    }

    private void startDuplicates() {
        if (Session.running) return;
        if (Session.items.isEmpty()) {
            error("请先扫描全部照片 / 视频，获得媒体清单后再查重。");
            return;
        }
        duplicateRequested = true;
        duplicateBaseline = Session.duplicateRevision;
        launch(new Intent(this, SortService.class).setAction("duplicates"));
    }

    private void maybeShowDuplicates() {
        if (!duplicateRequested || Session.running) return;
        if (Session.duplicateRevision == duplicateBaseline) return;
        duplicateRequested = false;
        DuplicateDialog.show(this, Session.duplicates, items -> startTrash(items, "重复项"));
    }

    private void startTrash(List<MediaItem> items, String action) {
        if (Session.running || items.isEmpty()) return;
        if (!Trash.supported()) {
            error("Android 11 以下没有可恢复的系统回收站；不会删除任何文件。");
            return;
        }
        List<Trash.Item> batch = new ArrayList<>();
        int missing = 0;
        for (MediaItem item : items) {
            if (!item.trashable()) { missing++; continue; }
            batch.add(new Trash.Item(item.mediaUri, item.name, sourcePath(item),
                    "系统回收站", item.size, ""));
        }
        if (batch.isEmpty()) {
            error("这些文件没有媒体库索引，无法放入系统回收站；原文件未改动。");
            return;
        }
        String note = missing > 0 ? "\n\n其中 " + missing + " 个文件没有媒体库索引，会保留原处。" : "";
        new AlertDialog.Builder(this)
                .setTitle("移入系统回收站 · " + batch.size() + " 个")
                .setMessage("系统会再次弹出确认框。移入的是「相册/文件管理」的回收站，可在系统回收站中恢复。"
                        + note + "\n\nApp 不会直接删除文件，也不会清空回收站。")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", (dialog, which) -> enqueueTrash(batch, action)).show();
    }

    private void enqueueTrash(List<Trash.Item> items, String action) {
        trashQueue.clear();
        trashQueue.addAll(items);
        trashTotal = items.size();
        trashDone = 0;
        trashFailed = 0;
        trashedUris.clear();
        trashAction = action;
        nextTrashChunk();
    }

    private void nextTrashChunk() {
        trashChunk.clear();
        if (trashQueue.isEmpty()) { finishTrash(); return; }
        while (!trashQueue.isEmpty() && trashChunk.size() < Trash.MAX_REQUEST) {
            trashChunk.add(trashQueue.poll());
        }
        try {
            IntentSender sender = Trash.request(getContentResolver(), trashChunk);
            startIntentSenderForResult(sender, TRASH, null, 0, 0, 0);
        } catch (Exception e) {
            trashFailed += trashChunk.size() + trashQueue.size();
            trashQueue.clear();
            logTrash(trashChunk, "TRASH_FAILED", e.getMessage());
            finishTrash();
            error("无法打开系统回收站确认框：" + e.getMessage());
        }
    }

    private void handleTrashResult(int result) {
        List<Trash.Item> chunk = new ArrayList<>(trashChunk);
        trashChunk.clear();
        if (result == RESULT_OK) {
            trashDone += chunk.size();
            for (Trash.Item item : chunk) trashedUris.add(item.uri());
            logTrash(chunk, "TRASHED", "用户已在系统弹窗确认，文件在系统回收站");
            if (!trashQueue.isEmpty()) { nextTrashChunk(); return; }
        } else {
            trashFailed += chunk.size() + trashQueue.size();
            trashQueue.clear();
            logTrash(chunk, "TRASH_CANCELLED", "用户取消，原文件保留");
        }
        finishTrash();
    }

    private void finishTrash() {
        if (trashAction.startsWith("重复")) removeTrashedDuplicates();
        if (pendingTrash != null) {
            Trash.clear(this);
            pendingTrash = null;
        }
        trashPrompted = false;
        if (trashDone > 0) Session.rescanRequested = true;
        Session.status = "回收站操作结束 · 已移入 " + trashDone + " 个 · 未移入 " + trashFailed
                + " 个；可在系统相册/文件管理的回收站中恢复";
        Ui.toastLong(this, "已移入回收站 " + trashDone + " 个");
    }

    private void logTrash(List<Trash.Item> items, String status, String detail) {
        try (Session.Journal journal = new Session.Journal(this)) {
            for (Trash.Item item : items) {
                journal.write(Session.eventName(status, item.name(), item.source(), item.target(),
                        (detail == null ? "" : detail) + ";sha256=" + item.hash()));
            }
            journal.write(Session.eventName("TRASH_BATCH", "",
                    "", "", "status=" + status + ";count=" + items.size()
                            + ";action=" + trashAction));
        } catch (Exception ignored) {
            // A failed log must not pretend a deletion happened.
        }
    }

    private void removeTrashedDuplicates() {
        if (trashedUris.isEmpty()) return;
        List<Duplicates.Group> updated = new ArrayList<>();
        for (Duplicates.Group group : Session.duplicates) {
            List<MediaItem> remaining = new ArrayList<>();
            for (MediaItem item : group.items()) if (!trashedUris.contains(item.mediaUri)) remaining.add(item);
            if (remaining.size() >= 2) {
                updated.add(new Duplicates.Group(group.hash(), group.size(),
                        java.util.Collections.unmodifiableList(remaining)));
            }
        }
        try { Session.saveDuplicates(this, updated); }
        catch (Exception e) { error("回收站已确认，但无法更新查重结果：" + e.getMessage()); }
    }

    private void maybePromptTrash() {
        if (trashPrompted || Session.running) return;
        Trash.Pending pending = pendingTrash != null ? pendingTrash : Trash.load(this);
        if (pending == null) return;
        if (pending.items().isEmpty()) { Trash.clear(this); return; }
        trashPrompted = true;
        pendingTrash = pending;
        if (!pending.complete()) {
            Trash.clear(this);
            pendingTrash = null;
            trashPrompted = false;
            Session.status = "复制未全部完成，未生成回收站操作；原文件保留";
            return;
        }
        String extra = pending.items().size() < pending.copied()
                ? "\n\n其中 " + (pending.copied() - pending.items().size())
                + " 个文件没有媒体库索引，无法移入回收站，将保留原件。" : "";
        new AlertDialog.Builder(this)
                .setCancelable(false)
                .setTitle("复制已全部校验")
                .setMessage("目标：" + pending.target() + "\n已复制并校验 " + pending.copied()
                        + "/" + pending.planned() + " 个文件。" + extra
                        + "\n\n是否将 " + pending.items().size()
                        + " 个原件移入系统回收站？\n回收站可恢复；选择“保留原件”则保留两份。")
                .setNegativeButton("保留原件", (dialog, which) -> {
                    Trash.clear(this);
                    pendingTrash = null;
                    trashPrompted = false;
                    Session.status = "已保留原件，未移入回收站";
                })
                .setPositiveButton("移入回收站", (dialog, which) ->
                        enqueueTrash(new ArrayList<>(pending.items()), pending.action()))
                .show();
    }

    private void confirm(boolean moving, int limit) {
        if (Session.running || draft.selected.isEmpty() || (!draft.inPlace && draft.target.isEmpty())) return;
        if (draft.inPlace) {
            Map<String, String> missing = new TreeMap<>();
            Map<String, Integer> locked = new TreeMap<>();
            boolean rescan = false;
            Storage.Grants grants = Storage.grants(getContentResolver());
            for (MediaItem item : Session.items) {
                String folder = Classification.folder(item, draft.rule);
                if (!included(item) || folder == null || !draft.selected.contains(folder)) continue;
                if (MediaPaths.ungrantable(item.parentId)) {
                    locked.merge(item.parentId, 1, Integer::sum);
                    continue;
                }
                try { grants.document(item.parentId); }
                catch (java.io.IOException e) { missing.put(item.parentId, MediaPaths.display(item.parentId)); }
                if (moving && !Storage.LOCAL.equals(Uri.parse(item.uri).getAuthority())) rescan = true;
            }
            if (!locked.isEmpty()) { promptLocked(locked, moving, limit); return; }
            if (!missing.isEmpty()) { promptAuthorization(missing); return; }
            if (rescan) { promptRescan(); return; }
        }
        int count = 0, pending = 0, assigned = 0;
        Map<String, Integer> trialCounts = new java.util.HashMap<>();
        for (MediaItem item : Session.items) {
            if (!included(item)) continue;
            String folder = Classification.folder(item, draft.rule);
            if (folder == null) { pending++; continue; }
            if (!draft.selected.contains(folder)) continue;
            String key = (draft.inPlace ? item.parentId + "/" : "") + folder + "|" + item.video;
            int prior = trialCounts.getOrDefault(key, 0);
            if (limit > 0 && prior >= limit) continue;
            trialCounts.put(key, prior + 1);
            count++;
            if (!item.manualFolder.isEmpty()) assigned++;
        }
        boolean trashNow = draft.trash && limit == 0;
        String message = ConfirmText.body(draft.inPlace
                        ? "各来源目录/" + MediaPaths.ARCHIVE + (Classification.FLAT.equals(draft.rule) ? "（不建子目录）" : "")
                        : targetDisplay(),
                Classification.title(draft.rule), count, assigned, pending, moving, limit, draft.move, trashNow);
        new AlertDialog.Builder(this).setTitle(ConfirmText.title(moving, trashNow, limit))
                .setMessage(message).setNegativeButton("取消", null).setPositiveButton("开始", (dialog, which) -> {
                    draft.active = false;
                    saveDraftState();
                    updateHub();
                    Intent intent = new Intent(this, SortService.class).setAction("execute")
                            .putExtra("target", draft.target).putExtra("move", moving).putExtra("limit", limit)
                            .putExtra("trash", trashNow)
                            .putExtra("inPlace", draft.inPlace)
                            .putStringArrayListExtra("excludedFolders", new ArrayList<>(draft.excludedFolders))
                            .putExtra("rule", draft.rule)
                            .putStringArrayListExtra("groups", new ArrayList<>(draft.selected))
                            .putStringArrayListExtra("sources", new ArrayList<>(draft.sources));
                    launch(intent);
                }).show();
    }

    private void promptAuthorization(Map<String, String> missing) {
        StringBuilder body = new StringBuilder("整理前需要先获得这些来源目录的读写授权。能查看到文件，不代表可以移动或写入它们。\n");
        int shown = 0;
        for (String path : missing.values()) {
            if (shown == 8) { body.append("\n…另有 ").append(missing.size() - 8).append(" 个目录"); break; }
            body.append("\n· ").append(path);
            shown++;
        }
        String covering = MediaPaths.coveringRoot(missing.keySet());
        if (!covering.isEmpty()) {
            body.append("\n\n这些目录都在 ").append(MediaPaths.display(covering))
                    .append(" 下，可一次授权整个目录，所有子目录自动生效；不必逐个点。");
        }
        body.append("\n\n逐个授权时，系统选择器会打开每个目录：停在目标文件夹，点“使用此文件夹”，并在系统弹窗中允许读写。");
        if (missing.size() > 1) body.append("\n\n不想整理的目录，也可以先点“取消”再取消勾选。");
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("需要授权 " + missing.size() + " 个目录")
                .setMessage(body.toString())
                .setNegativeButton("取消", null)
                .setPositiveButton(covering.isEmpty() ? "去授权" : "逐个授权", (d, which) -> {
                    authQueue.clear();
                    authQueue.addAll(missing.keySet());
                    authorizeNext();
                });
        if (!covering.isEmpty()) {
            dialog.setNeutralButton("授权整个 " + MediaPaths.relative(covering), (d, which) -> {
                int colon = covering.indexOf(':');
                authorizeBroad(colon < 0 ? covering : covering.substring(colon + 1));
            });
        }
        dialog.show();
    }

    /**
     * 放在最外层目录（存储根、Download 根）的文件永远拿不到读写授权：系统选择器对这些目录
     * 禁用“使用此文件夹”。这里一次说清原因，并让用户跳过它们继续整理其余目录，而不是反复
     * 打开一个点不通的选择器。
     */
    private void promptLocked(Map<String, Integer> locked, boolean moving, int limit) {
        int sum = 0;
        for (int count : locked.values()) sum += count;
        final int files = sum;
        int remaining = 0;
        for (MediaItem item : Session.items) {
            String folder = Classification.folder(item, draft.rule);
            if (!included(item) || folder == null || !draft.selected.contains(folder)) continue;
            if (!locked.containsKey(item.parentId)) remaining++;
        }
        StringBuilder body = new StringBuilder("这些文件就在存储的最外层目录：");
        for (Map.Entry<String, Integer> entry : locked.entrySet()) {
            body.append("\n· ").append(MediaPaths.lockedName(entry.getKey()))
                    .append(" · ").append(entry.getValue()).append(" 个文件");
        }
        body.append("\n\n系统不允许把最外层目录授权给应用：在目录选择器里，这些目录的“使用此文件夹”是灰的。")
                .append("这不是权限没给，是系统不给。就地整理需要在文件所在目录里新建“分类”，")
                .append("所以这些文件只能跳过。");
        if (remaining > 0) {
            body.append("\n\n跳过这 ").append(files).append(" 个文件后，其余 ").append(remaining)
                    .append(" 个照常整理，跳过的文件保持原样。想让它们也归档，请先取消“就地分类”，")
                    .append("选好目标目录后改用复制。");
        } else {
            body.append("\n\n其余文件都不在本次整理范围内。")
                    .append("请先取消“就地分类”，选好目标目录后改用复制。");
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(files + " 个文件不能就地整理")
                .setMessage(body.toString())
                .setNegativeButton("取消", null);
        if (remaining > 0) {
            dialog.setPositiveButton("跳过这 " + files + " 个文件",
                    (d, which) -> skipLocked(locked, files, moving, limit));
        } else {
            dialog.setPositiveButton("知道了", null);
        }
        dialog.show();
    }

    /** 跳过系统不授予的目录：在来源目录列表里取消勾选，之后再次核对并继续。 */
    private void skipLocked(Map<String, Integer> locked, int files, boolean moving, int limit) {
        for (String parent : locked.keySet()) {
            draft.excludedFolders.add(parent);
            CheckBox check = discoveredChecks.get(parent);
            if (check == null) continue;
            syncing = true;
            try { check.setChecked(false); }
            finally { syncing = false; }
        }
        persistFolders();
        markDraftChanged();
        updateFolderBlock();
        scheduleGroupRefresh();
        Session.status = "已跳过 " + files + " 个文件：所在目录不允许授权";
        if (status != null) status.setText("● " + Session.status);
        confirm(moving, limit);
    }

    /** One SAF grant on DCIM or Pictures covers all descendants; no per-folder tap. */
    private void authorizeBroad(String relative) {
        if (Session.running) return;
        if (!relative.equals("DCIM") && !relative.equals("Pictures")) return;
        Intent intent = directoryIntent().putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri(Storage.LOCAL, "primary:" + relative));
        try { startActivityForResult(intent, BROAD); }
        catch (RuntimeException e) { error("无法打开系统目录选择器：" + e.getMessage()); }
    }

    private void authorizeBroadGranted(Uri uri, Intent data) {
        try {
            String id = Storage.id(uri);
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) throw new java.io.IOException("未获得读取授权");
            if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) throw new java.io.IOException("未获得写入授权");
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            renderDiscovered();
            Session.status = "已授权 " + MediaPaths.display(id) + " 及其所有子目录，可直接整理";
            Ui.toastLong(this, "已授权 " + MediaPaths.display(id) + "（含子目录）");
        } catch (Exception e) {
            error("整目录授权未完成：" + e.getMessage() + "\n\n请在系统选择器中停留在 DCIM 或 Pictures 本身，再点“使用此文件夹”。");
        }
    }

    private void authorizeNext() {
        if (Session.running) { authQueue.clear(); authTarget = ""; return; }
        authTarget = authQueue.poll();
        if (authTarget == null) { authTarget = ""; return; }
        Intent intent = directoryIntent().putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri(Storage.LOCAL, authTarget));
        try { startActivityForResult(intent, AUTH); }
        catch (RuntimeException e) { authTarget = ""; authQueue.clear(); error("无法打开系统目录选择器：" + e.getMessage()); }
    }

    private void authorizeGranted(Uri uri, Intent data) {
        String docId = authTarget;
        authTarget = "";
        try {
            Storage.id(uri);
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) throw new java.io.IOException("未获得读取授权");
            if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) throw new java.io.IOException("未获得写入授权");
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            authQueue.clear();
            error("授权未完成：" + e.getMessage() + "\n\n请重新整理，并在系统选择器中允许读写。");
            return;
        }
        try { if (!docId.isEmpty()) Storage.authorizedDocument(getContentResolver(), docId); }
        catch (java.io.IOException e) {
            authQueue.clear();
            error("所选目录不包含 " + MediaPaths.display(docId) + "。\n\n请重新整理，在系统选择器中停在 "
                    + MediaPaths.display(docId) + " 本身（不要进入子文件夹），再点“使用此文件夹”。");
            return;
        }
        for (Iterator<String> it = authQueue.iterator(); it.hasNext(); ) {
            try { Storage.authorizedDocument(getContentResolver(), it.next()); it.remove(); }
            catch (java.io.IOException ignored) { }
        }
        if (!authQueue.isEmpty()) { authorizeNext(); return; }
        rescanAfterAuthorization();
    }

    private void rescanAfterAuthorization() {
        if (scannedAll) {
            Session.status = "目录已授权，正在重新查找…";
            status.setText("● " + Session.status);
            startDiscovery();
        } else if (!draft.sources.isEmpty()) {
            Session.status = "目录已授权，正在重新扫描…";
            status.setText("● " + Session.status);
            startScan();
        } else {
            Ui.toastLong(this, "目录已授权，请重新“扫描全部照片 / 视频”后再整理");
        }
    }

    private void promptRescan() {
        new AlertDialog.Builder(this).setTitle("需要重新查找")
                .setMessage("目录授权已更新，旧扫描结果不能直接移动。重新查找后即可继续。")
                .setNegativeButton("取消", null)
                .setPositiveButton("重新查找", (dialog, which) -> rescanAfterAuthorization()).show();
    }

    private void launch(Intent intent) {
        if (Session.running) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 20);
        try {
            prefs.edit().putBoolean("jobStarted", true).apply();
            startForegroundService(intent);
        } catch (RuntimeException e) { error("任务无法启动：" + e.getMessage()); }
    }

    private void persist() {
        saveDraftState();
    }

    private void saveDraftState() {
        if (store != null) store.save(draft);
    }

    private void markDraftChanged() {
        if (syncing) return;
        draft.active = true;
        saveDraftState();
        updateHub();
    }

    private void showScreen(int destination) {
        dismissHint();
        screen = destination;
        if (hubScreen != null) hubScreen.setVisibility(destination == SCREEN_HUB ? View.VISIBLE : View.GONE);
        if (sortScreen != null) sortScreen.setVisibility(destination == SCREEN_SORT ? View.VISIBLE : View.GONE);
        if (toolsScreen != null) toolsScreen.setVisibility(destination == SCREEN_TOOLS ? View.VISIBLE : View.GONE);
        showBottomNav();
        if (nav != null) nav.select(destination);
        View shown = destination == SCREEN_SORT ? sortScreen : destination == SCREEN_TOOLS ? toolsScreen : hubScreen;
        if (shown != null) {
            shown.setAlpha(0.84f);
            shown.animate().alpha(1f).setDuration(180).start();
        }
        if (destination == SCREEN_SORT) {
            applyViewMode();
        } else if (destination == SCREEN_HUB) {
            updateHub();
        } else if (toolsStatus != null) {
            toolsStatus.setText(Session.status);
        }
    }

    private void enterSort() {
        boolean fromHub = screen != SCREEN_SORT;
        if (fromHub && !draft.active) draft.step = 0;
        showScreen(SCREEN_SORT);
        renderSources();
        renderDiscovered();
        renderGroups();
        applyViewMode();
        updateReview();
        updateWizardFooter();
        saveDraftState();
        if (sortScroll != null) sortScroll.scrollTo(0, 0);
    }

    private void enterTools() {
        showScreen(SCREEN_TOOLS);
        if (toolsStatus != null) toolsStatus.setText(Session.status);
    }

    private void showHub() {
        showScreen(SCREEN_HUB);
    }

    private void leaveSort() {
        if (Session.running) {
            new AlertDialog.Builder(this).setTitle("任务正在后台运行")
                    .setMessage("离开这一页不会停止整理。可以回到首页，稍后再查看进度。")
                    .setNegativeButton("继续查看", null)
                    .setPositiveButton("回到首页", (dialog, which) -> showHub())
                    .show();
            return;
        }
        if (!draft.active) { showHub(); return; }
        new AlertDialog.Builder(this)
                .setTitle("保留未完成的设置？")
                .setMessage("当前在" + Wizard.badge(draft.step) + "（" + Wizard.title(draft.step) + "）。\n\n"
                        + "保存后下次进入“文件移动分类”会从这一步继续，来源、目标、策略和勾选都不会丢失。")
                .setPositiveButton("保存并退出", (dialog, which) -> {
                    saveDraftState();
                    showHub();
                })
                .setNeutralButton("继续设置", null)
                .setNegativeButton("舍弃", (dialog, which) -> confirmDiscard(this::showHub))
                .show();
    }

    private void confirmDiscard() {
        confirmDiscard(null);
    }

    private void confirmDiscard(Runnable after) {
        new AlertDialog.Builder(this)
                .setTitle("舍弃未完成的设置？")
                .setMessage("会重置来源、目标、分类策略和执行方式；已扫描的媒体列表会保留，分类勾选回到默认。"
                        + "不会移动或删除任何文件。")
                .setNegativeButton("取消", null)
                .setPositiveButton("舍弃设置", (dialog, which) -> {
                    discardDraft();
                    if (after != null) after.run();
                })
                .show();
    }

    private void discardDraft() {
        if (Session.running) {
            Ui.toast(this, "任务进行中，暂时不能舍弃设置");
            return;
        }
        draft.reset();
        syncing = true;
        try {
            if (locationCheck != null) locationCheck.setChecked(true);
            if (copyMode != null) copyMode.setChecked(true);
            if (strategy != null) strategy.setText(Classification.title(draft.rule));
            if (targetLabel != null) targetLabel.setText(locationLabel());
        } finally {
            syncing = false;
        }
        renderSources();
        renderDiscovered();
        renderGroups();
        saveDraftState();
        updateReview();
        updateWizardFooter();
        updateHub();
    }

    private void toggleViewMode() {
        boolean toWizard = !VIEW_WIZARD.equals(draft.viewMode);
        if (toWizard && sortScreen != null && sortScreen.getVisibility() == View.VISIBLE) {
            syncStepToScroll();
        }
        draft.viewMode = toWizard ? VIEW_WIZARD : VIEW_SCROLL;
        saveDraftState();
        applyViewMode();
        updateHub();
        if (sortScroll != null) {
            if (VIEW_WIZARD.equals(draft.viewMode) || draft.step == 0) {
                sortScroll.post(() -> sortScroll.scrollTo(0, 0));
            } else {
                final View current = draft.step == 1 ? stepTwo : stepThree;
                sortScroll.post(() -> sortScroll.scrollTo(0, current == null ? 0 : current.getTop()));
            }
        }
        Ui.toast(this, VIEW_WIZARD.equals(draft.viewMode)
                ? "已切换为分步模式：每一步只做一件事"
                : "已切换为单页模式：所有设置在一页内滚动");
    }

    private void syncStepToScroll() {
        if (sortScroll == null || stepTwo == null || stepThree == null) return;
        int y = sortScroll.getScrollY();
        if (y >= stepThree.getTop() - dp(140)) draft.step = 2;
        else if (y >= stepTwo.getTop() - dp(140)) draft.step = 1;
        else draft.step = 0;
    }

    private void applyViewMode() {
        if (stepHeader == null || wizardFooter == null) return;
        boolean wizard = VIEW_WIZARD.equals(draft.viewMode);
        stepHeader.setVisibility(wizard ? View.VISIBLE : View.GONE);
        wizardFooter.setVisibility(wizard ? View.VISIBLE : View.GONE);
        if (stepOne != null) stepOne.setVisibility(!wizard || draft.step == 0 ? View.VISIBLE : View.GONE);
        if (stepTwo != null) stepTwo.setVisibility(!wizard || draft.step == 1 ? View.VISIBLE : View.GONE);
        if (stepThree != null) stepThree.setVisibility(!wizard || draft.step == 2 ? View.VISIBLE : View.GONE);
        // 分步模式第 3 步由底部主按钮负责开始，页面里不再重复一个同样的实心按钮。
        if (execute != null) execute.setVisibility(wizard ? View.GONE : View.VISIBLE);
        if (sectionOne != null) sectionOne.setVisibility(wizard ? View.GONE : View.VISIBLE);
        if (sectionTwo != null) sectionTwo.setVisibility(wizard ? View.GONE : View.VISIBLE);
        if (sectionThree != null) sectionThree.setVisibility(wizard ? View.GONE : View.VISIBLE);
        if (modeToggle != null) {
            modeToggle.setText(wizard ? "分步" : "单页");
            modeToggle.setContentDescription(wizard
                    ? "当前分步视图，点按切换为单页"
                    : "当前单页视图，点按切换为分步");
        }
        if (sortPage != null) {
            // Footer wrap owns the bottom breathing room now that the footer scrolls
            // with the page instead of sitting in its own pinned layer.
            sortPage.setPadding(dp(16), dp(16), dp(16), 0);
        }
        updateStepHeader();
        updateWizardFooter();
        updateReview();
    }

    private void updateStepHeader() {
        if (stepBadge == null) return;
        stepBadge.setText(Wizard.badge(draft.step));
        stepTitle.setText(Wizard.title(draft.step));
        stepHint.setText(Wizard.hint(draft.step));
    }

    private void wizardNext() {
        if (Session.running) {
            Ui.toast(this, "任务正在进行，请等待完成");
            return;
        }
        String blocker = Wizard.blockNext(draft.step, !Session.items.isEmpty(), !draft.selected.isEmpty(),
                draft.inPlace, !draft.target.isEmpty());
        if (blocker != null) {
            Ui.toastLong(this, blocker);
            return;
        }
        if (draft.step < Wizard.STEPS - 1) goStep(draft.step + 1, true);
        else confirm(draft.move, 0);
    }

    private void goStep(int value, boolean smooth) {
        draft.setStep(value);
        store.saveStep(draft);
        applyViewMode();
        if (sortScroll != null) {
            if (smooth) sortScroll.smoothScrollTo(0, 0);
            else sortScroll.scrollTo(0, 0);
        }
        saveDraftState();
    }

    private void updateWizardFooter() {
        if (prevStep == null || nextStep == null) return;
        boolean busy = Session.running;
        String blocker = busy ? "任务进行中，请等待当前读写结束"
                : Wizard.blockNext(draft.step, !Session.items.isEmpty(), !draft.selected.isEmpty(),
                        draft.inPlace, !draft.target.isEmpty());
        boolean prevEnabled = !busy && draft.step > 0;
        if (prevStep.isEnabled() != prevEnabled) prevStep.setEnabled(prevEnabled);
        float prevAlpha = prevEnabled ? 1f : .4f;
        if (prevStep.getAlpha() != prevAlpha) prevStep.setAlpha(prevAlpha);
        boolean nextEnabled = !busy && blocker == null;
        setPrimaryEnabled(nextStep, nextEnabled);
        String nextLabel = Wizard.nextLabel(draft.step, draft.move, draft.trash);
        layoutWizardActions(nextLabel.length() > 6);
        if (!nextStep.getText().toString().equals(nextLabel)) nextStep.setText(nextLabel);
        if (wizardBlocker != null) {
            String text = blocker == null ? "" : blocker;
            if (!wizardBlocker.getText().toString().equals(text)) wizardBlocker.setText(text);
            int visibility = blocker == null ? View.GONE : View.VISIBLE;
            if (wizardBlocker.getVisibility() != visibility) wizardBlocker.setVisibility(visibility);
        }
    }

    private void updateReview() {
        if (review == null) return;
        long[] stats = selectedStats();
        String destination = draft.inPlace
                ? "各来源目录 / " + MediaPaths.ARCHIVE
                        + (Classification.FLAT.equals(draft.rule) ? "（不建子目录）" : "")
                : draft.target.isEmpty() ? "未选择目标目录" : targetDisplay();
        StringBuilder body = new StringBuilder();
        body.append("目标：").append(destination)
                .append("\n分类：").append(Classification.title(draft.rule))
                .append("\n已选：").append(draft.selected.size()).append(" 个目录 · ")
                .append(stats[0]).append(" 个文件 · ").append(size(stats[1]))
                .append("\n方式：").append(modeLabel());
        if (Session.items.isEmpty()) {
            body.append("\n\n还没有扫描结果，请先扫描手机媒体。");
        } else if (stats[0] == 0) {
            body.append("\n\n没有勾选可执行的文件，请选择分类策略并勾选目录。");
        } else if (!draft.inPlace && draft.target.isEmpty()) {
            body.append("\n\n请先选择独立目标目录，或改为就地分类。");
        }
        review.setText(body.toString());
    }

    private long[] selectedStats() {
        long bytes = 0;
        long count = 0;
        for (MediaItem item : Session.items) {
            if (!included(item)) continue;
            String folder = Classification.folder(item, draft.rule);
            if (folder != null && draft.selected.contains(folder)) {
                count++;
                bytes += Math.max(0, item.size);
            }
        }
        return new long[]{count, bytes};
    }

    private void updateHub() {
        if (hubDraft == null) return;
        boolean wizard = VIEW_WIZARD.equals(draft.viewMode);
        if (hubModeToggle != null) {
            hubModeToggle.setText(wizard ? "分步" : "单页");
            hubModeToggle.setContentDescription(wizard
                    ? "当前分步视图，点按切换为单页"
                    : "当前单页视图，点按切换为分步");
        }
        if (hubStatus != null) hubStatus.setText(Session.status);
        if (Session.running) {
            hubDraft.setText("任务进行中 · 点此查看进度");
            hubEnter.setText("查看任务进度");
            if (hubDiscard != null) hubDiscard.setVisibility(View.GONE);
        } else if (draft.active) {
            hubDraft.setText("未完成设置 · 已选 " + draft.selected.size() + " 个分类");
            hubEnter.setText("继续未完成设置");
            if (hubDiscard != null) hubDiscard.setVisibility(View.VISIBLE);
        } else {
            hubDraft.setText("尚未开始设置");
            hubEnter.setText("开始设置");
            if (hubDiscard != null) hubDiscard.setVisibility(View.GONE);
        }
    }

    private void showStatusDialog() {
        new AlertDialog.Builder(this)
                .setTitle("当前状态")
                .setMessage(Session.status + (Session.running
                        ? "\n\n任务正在进行，可以回到首页稍后查看。" : ""))
                .setPositiveButton("确定", null)
                .show();
    }

    private void invalidateScan() {
        try { Session.saveScan(this, java.util.Collections.emptyList()); Session.status = "目录已更新，请重新查找或扫描"; }
        catch (Exception e) { error("无法清除旧预览：" + e.getMessage()); }
        draft.selected.clear();
        draft.knownGroups.clear();
    }

    private String groupLabel(String folder) {
        return folder == null || folder.isEmpty() ? "直接放入目标目录（不建子目录）" : folder;
    }

    private String label(String uri) {
        try { return DocumentsContract.getTreeDocumentId(Uri.parse(uri)).replace("primary:", "内部存储 / "); }
        catch (Exception e) { return uri; }
    }
    private static String size(long bytes) {
        if (bytes >= 1073741824) return String.format(Locale.getDefault(), "%.2f GB", bytes / 1073741824.0);
        if (bytes >= 1048576) return String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
    }
private LinearLayout cardBox() { return Ui.card(this); }

    private void addCard(LinearLayout parent, View card) { Ui.addCard(parent, card); }

    private LinearLayout column() { return Ui.column(this); }

    private LinearLayout row() { return Ui.row(this); }

    private TextView text(String value, int size, int color, boolean bold) {
        return Ui.label(this, value, size, color, bold);
    }

    private Button button(String value, int color, View.OnClickListener listener) {
        return Ui.primaryButton(this, value, color, listener);
    }

    /** Secondary action: hairline outline, no fill, so one solid button keeps the page hierarchy. */
    private Button quietButton(String value, View.OnClickListener listener) {
        return Ui.quietButton(this, value, listener);
    }

    /** Disabled primary actions go quiet instead of fading to an ambiguous grey. */
    private void setPrimaryEnabled(Button button, boolean enabled) {
        Ui.setPrimaryEnabled(button, enabled);
    }

    private Ui.Action smallButton(String value, int color, View.OnClickListener listener) {
        return Ui.smallAction(this, value, color, listener);
    }

    private String duration(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600,
                (seconds % 3600) / 60, seconds % 60);
    }

    private ImageButton icon(int drawable, String description, View.OnClickListener listener) {
        return Ui.iconButton(this, drawable, description, listener);
    }

    /** Section header: mono number chip, title, then a tap-only explanation. */
    private View section(LinearLayout parent, String number, String title) {
        return Ui.sectionHeader(this, parent, number, title);
    }

    private void sectionLabel(LinearLayout parent, String value) {
        Ui.sectionLabel(this, parent, value);
    }

    /** Small circular "?" that opens a bubble above the finger, never under it. */
    private View hint(final String message) {
        return Ui.hintButton(this, message, v -> showHint(v, message));
    }

    private View toolRow(int iconRes, String title, String subtitle, View.OnClickListener listener) {
        return Ui.toolRow(this, iconRes, title, subtitle, listener);
    }

    private void separator(LinearLayout parent, int insetDp) {
        Ui.hairline(parent, insetDp);
    }

    private void setSummary(long photos, long videos, long unknown) {
        if (summaryBox != null) {
            summaryBox.setVisibility(Session.items.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (summaryPhotos != null) summaryPhotos.setText(String.valueOf(photos));
        if (summaryVideos != null) summaryVideos.setText(String.valueOf(videos));
        if (summaryPending != null) {
            summaryPending.setText(String.valueOf(unknown));
            summaryPending.setTextColor(unknown > 0 ? Ui.INK : Ui.FAINT);
        }
    }

    /** Tab tap when that page is not on screen yet. */
    private void openTab(int page) {
        if (page == SCREEN_HUB) showScreen(SCREEN_HUB);
        else if (page == SCREEN_SORT) enterSort();
        else enterTools();
    }

    /** Tapping the tab of the page already on screen returns it to the top. */
    private void scrollToTop(int page) {
        ScrollView pageView = page == SCREEN_HUB ? hubScroll
                : page == SCREEN_SORT ? sortScroll : toolsScroll;
        if (pageView != null) pageView.smoothScrollTo(0, 0);
    }

    /**
     * Scrolling down puts the bar away so the page keeps the whole screen. Scrolling
     * up does not animate the bar while the content is still moving: the reveal is
     * scheduled for the end of the scroll, so a fling never fights the bar animation.
     */
    private void onScreenScroll(View source, int dy) {
        if (Math.abs(dy) < dp(1)) return;
        dismissHint();
        handler.removeCallbacks(revealNav);
        boolean atTop = source != null && !source.canScrollVertically(-1);
        if (dy > 0 && !atTop) {
            hideBottomNav();
        } else {
            handler.postDelayed(revealNav, NAV_REVEAL_DELAY);
        }
    }

    private void hideBottomNav() {
        if (nav != null) nav.hide();
    }

    private void showBottomNav() {
        if (nav != null) nav.show();
    }

    private void showHint(View anchor, String message) {
        if (hintPopup == null) hintPopup = new HintPopup(this, root);
        hintPopup.show(anchor, message);
    }

    private void dismissHint() {
        if (hintPopup != null) hintPopup.dismiss();
    }

    private void space(LinearLayout parent, int height) { Ui.space(parent, height); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void error(String message) { new AlertDialog.Builder(this).setTitle("提示").setMessage(message).setPositiveButton("确定", null).show(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        logs.save(state);
        store.saveState(state, draft);
        state.putString("authTarget", authTarget);
        state.putStringArrayList("authQueue", new ArrayList<>(authQueue));
        state.putInt("screen", screen);
    }

    @Override public void onBackPressed() {
        if (screen == SCREEN_SORT) { leaveSort(); return; }
        if (screen == SCREEN_TOOLS) { showHub(); return; }
        super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        registerMediaObserver();
        showBottomNav();
        handler.post(refresh);
        // Catch files added while this page was in the background. The generation
        // check makes this a no-op when nothing changed, so it does not rescan.
        scheduleMediaCheck(800);
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        dismissHint();
        unregisterMediaObserver();
        handler.removeCallbacks(mediaCheck);
        handler.removeCallbacks(refresh);
        saveDraftState();
    }
}
