package com.vagiiark.tv;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    public static final String GITHUB_OWNER = "Vagii-Kavagi";
    public static final String GITHUB_REPO = "vagiiark-tv";
    private static final String GITHUB_RELEASES_API =
            "https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/latest";
    private static final String GITHUB_HOTFIX_JS_URL =
            "https://raw.githubusercontent.com/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/main/hotfix/vagiiark-patch.js";

    // Clean SmartTV User-Agent that triggers YouTube TV's standard vibrant theme
    private static final String YT_TV_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9; SmartTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";

    private static final String YT_TV_URL = "https://www.youtube.com/tv";
    private static final String PREFS_NAME = "vagiiark_prefs";

    private static final String[] AD_HOST_PATTERNS = new String[]{
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.",
            "pagead2.googlesyndication.com",
            "imasdk.googleapis.com",
            "youtube.com/api/stats/ads",
            "youtube.com/pagead/",
            "youtube.com/ptracking",
            "youtube.com/get_midroll_info"
    };

    private static final double[] SPEED_PRESETS = new double[]{
            0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0
    };

    // { categoryKey, displayLabel, hexColor, defaultMode }
    private static final String[][] SPONSOR_CATEGORIES = new String[][]{
            {"sponsor", "Sponsor Segments", "#00E676", "auto"},
            {"selfpromo", "Unpaid / Self Promotion", "#FFD600", "auto"},
            {"interaction", "Subscribe / Like Reminders", "#E040FB", "auto"},
            {"intro", "Intermission / Intro Animation", "#00E5FF", "manual"},
            {"outro", "Endcards & Credits", "#3D5AFE", "manual"},
            {"preview", "Preview / Recap", "#00B0FF", "manual"},
            {"music_offtopic", "Music: Non-Music Section", "#FF9100", "auto"}
    };

    private FrameLayout rootLayout;
    private WebView webView;
    private FrameLayout quickMenuScrim;
    private LinearLayout quickMenuContainer;
    private TextView toastBanner;
    private TextView statusHeaderView;

    private SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();

    private boolean adblockEnabled = true;
    private boolean sponsorBlockEnabled = true;
    private double playbackSpeed = 1.0;
    private String videoAspectMode = "fit"; // "fit" (Default 1:1), "fill", "stretch"
    private boolean fastMode = true;

    private int adsBlockedCount = 0;
    private int segmentsSkippedCount = 0;
    private long secondsSavedCount = 0;

    private String injectedScriptContent = "";
    private long okKeyDownTime = 0;
    private boolean okLongPressTriggered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        loadPrefs();
        freeBackgroundHeavyApps();
        loadInjectedScript();

        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.BLACK);

        setupWebView();
        rootLayout.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        setupToastBanner();
        setupQuickMenuOverlay();

        setContentView(rootLayout);

        String startUrl = resolveStartUrl(getIntent());
        webView.loadUrl(startUrl);

        syncHotfixFromGitHub(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = resolveStartUrl(intent);
        if (webView != null && !TextUtils.isEmpty(url)) {
            webView.loadUrl(url);
        }
    }

    private String resolveStartUrl(Intent intent) {
        if (intent != null) {
            Uri data = intent.getData();
            if (data != null) {
                String vid = extractVideoId(data.toString());
                if (!TextUtils.isEmpty(vid)) {
                    return "https://www.youtube.com/tv#/watch?v=" + vid;
                }
            }
            if (Intent.ACTION_SEND.equals(intent.getAction())) {
                String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
                String vid = extractVideoId(shared);
                if (!TextUtils.isEmpty(vid)) {
                    return "https://www.youtube.com/tv#/watch?v=" + vid;
                }
            }
        }
        return YT_TV_URL;
    }

    private String extractVideoId(String text) {
        if (TextUtils.isEmpty(text)) return null;
        try {
            Uri u = Uri.parse(text.trim());
            String qv = u.getQueryParameter("v");
            if (!TextUtils.isEmpty(qv)) return qv;
            if ("youtu.be".equalsIgnoreCase(u.getHost())) {
                String seg = u.getLastPathSegment();
                if (!TextUtils.isEmpty(seg)) return seg;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void freeBackgroundHeavyApps() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            String[] heavyPackages = new String[]{
                    "com.nuvio.livetv",
                    "com.nuvio.tv",
                    "com.netflix.ninja",
                    "com.stremio.one",
                    "com.wbd.stream"
            };
            for (String pkg : heavyPackages) {
                try {
                    am.killBackgroundProcesses(pkg);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void loadPrefs() {
        adblockEnabled = prefs.getBoolean("adblock_enabled", true);
        sponsorBlockEnabled = prefs.getBoolean("sponsorblock_enabled", true);
        playbackSpeed = (double) prefs.getFloat("playback_speed", 1.0f);
        videoAspectMode = prefs.getString("video_aspect_mode", "fit");
        fastMode = prefs.getBoolean("fast_mode", true);

        adsBlockedCount = prefs.getInt("stat_ads_blocked", 0);
        segmentsSkippedCount = prefs.getInt("stat_segments_skipped", 0);
        secondsSavedCount = prefs.getLong("stat_seconds_saved", 0L);
    }

    private String getCategoryMode(String cat, String defaultMode) {
        String key = "sb_mode_" + cat;
        if (prefs.contains(key)) {
            return prefs.getString(key, defaultMode);
        }
        String legacyKey = "sb_cat_" + cat;
        if (prefs.contains(legacyKey)) {
            try {
                return prefs.getBoolean(legacyKey, true) ? defaultMode : "off";
            } catch (Exception ignored) {
            }
        }
        return defaultMode;
    }

    private void setCategoryMode(String cat, String mode) {
        prefs.edit().putString("sb_mode_" + cat, mode).apply();
    }

    private String nextCategoryMode(String current) {
        if ("auto".equals(current)) return "manual";
        if ("manual".equals(current)) return "off";
        return "auto";
    }

    private String formatCategoryModeBadge(String mode) {
        if ("auto".equals(mode)) return "AUTO-SKIP";
        if ("manual".equals(mode)) return "MANUAL (PRESS OK)";
        return "OFF";
    }

    private void loadInjectedScript() {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = getAssets().open("vagiiark.js");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception ignored) {
        }
        File patchFile = new File(getFilesDir(), "github_hotfix.js");
        if (patchFile.exists() && patchFile.length() > 0) {
            try (FileInputStream fis = new FileInputStream(patchFile);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
                sb.append("\n/* --- GitHub Hotfix Patch --- */\n");
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            } catch (Exception ignored) {
            }
        }
        injectedScriptContent = sb.toString();
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void setupWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setInitialScale(0);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setUserAgentString(YT_TV_USER_AGENT);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setSupportZoom(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new VagiiJsBridge(), "VagiiBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }

            @Override
            public Bitmap getDefaultVideoPoster() {
                Bitmap bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.TRANSPARENT);
                return bmp;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (adblockEnabled && request != null && request.getUrl() != null) {
                    String urlStr = request.getUrl().toString();
                    for (String pattern : AD_HOST_PATTERNS) {
                        if (urlStr.contains(pattern)) {
                            recordAdBlocked();
                            return new WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    204,
                                    "No Content",
                                    null,
                                    new ByteArrayInputStream(new byte[0])
                            );
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                injectEngineScripts();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectEngineScripts();
                pushConfigToJs();
            }
        });
    }

    private void injectEngineScripts() {
        if (webView == null || TextUtils.isEmpty(injectedScriptContent)) return;
        mainHandler.post(() -> {
            try {
                webView.evaluateJavascript(injectedScriptContent, null);
                pushConfigToJs();
            } catch (Exception ignored) {
            }
        });
    }

    private void pushConfigToJs() {
        if (webView == null) return;
        try {
            String json = buildConfigJson().toString();
            webView.evaluateJavascript(
                    "if(window.__vagiiArkUpdateConfig){window.__vagiiArkUpdateConfig(" + json + ");}",
                    null
            );
        } catch (Exception ignored) {
        }
    }

    private JSONObject buildConfigJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("adblockEnabled", adblockEnabled);
            obj.put("sponsorBlockEnabled", sponsorBlockEnabled);
            obj.put("speed", playbackSpeed);
            obj.put("videoAspectMode", videoAspectMode);
            obj.put("fastMode", fastMode);

            JSONObject cats = new JSONObject();
            for (String[] catDef : SPONSOR_CATEGORIES) {
                String cat = catDef[0];
                String defaultMode = catDef[3];
                cats.put(cat, getCategoryMode(cat, defaultMode));
            }
            obj.put("categories", cats);

            JSONObject stats = new JSONObject();
            stats.put("adsBlocked", adsBlockedCount);
            stats.put("segmentsSkipped", segmentsSkippedCount);
            stats.put("secondsSaved", secondsSavedCount);
            obj.put("stats", stats);
        } catch (Exception ignored) {
        }
        return obj;
    }

    private void recordAdBlocked() {
        adsBlockedCount++;
        prefs.edit().putInt("stat_ads_blocked", adsBlockedCount).apply();
    }

    private void setupToastBanner() {
        toastBanner = new TextView(this);
        toastBanner.setTextColor(Color.WHITE);
        toastBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        toastBanner.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        toastBanner.setPadding(dp(18), dp(12), dp(18), dp(12));
        toastBanner.setAlpha(0f);

        FrameLayout.LayoutParams toastLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END
        );
        toastLp.setMargins(0, 0, dp(36), dp(36));
        rootLayout.addView(toastBanner, toastLp);
    }

    private void showTvToast(String message, String hexAccent) {
        mainHandler.post(() -> {
            if (toastBanner == null) return;
            int accent = Color.parseColor("#00E676");
            try {
                if (!TextUtils.isEmpty(hexAccent)) accent = Color.parseColor(hexAccent);
            } catch (Exception ignored) {
            }
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#EB10131A"));
            bg.setCornerRadius(dp(14));
            bg.setStroke(dp(2), accent);
            toastBanner.setBackground(bg);
            toastBanner.setText(message);
            toastBanner.animate().cancel();
            toastBanner.setAlpha(1f);
            mainHandler.removeCallbacks(hideToastRunnable);
            mainHandler.postDelayed(hideToastRunnable, 2700);
        });
    }

    private final Runnable hideToastRunnable = () -> {
        if (toastBanner != null) {
            toastBanner.animate().alpha(0f).setDuration(250).start();
        }
    };

    // ---------------------------------------------------------------------------
    // VAGIIARK TV CUSTOM IN-APP SETTINGS MENU OVERLAY (D-Pad Friendly)
    // ---------------------------------------------------------------------------
    private void setupQuickMenuOverlay() {
        quickMenuScrim = new FrameLayout(this);
        quickMenuScrim.setBackgroundColor(Color.parseColor("#B805070B"));
        quickMenuScrim.setVisibility(View.GONE);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setScrollbarFadingEnabled(false);
        FrameLayout.LayoutParams scrollLp = new FrameLayout.LayoutParams(
                dp(480),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END
        );
        quickMenuScrim.addView(scrollView, scrollLp);

        quickMenuContainer = new LinearLayout(this);
        quickMenuContainer.setOrientation(LinearLayout.VERTICAL);
        quickMenuContainer.setPadding(dp(24), dp(40), dp(24), dp(28));
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(Color.parseColor("#F512151E"));
        panelBg.setStroke(dp(1), Color.parseColor("#33FFFFFF"));
        quickMenuContainer.setBackground(panelBg);

        scrollView.addView(quickMenuContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        rootLayout.addView(quickMenuScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    public boolean isQuickMenuOpen() {
        return quickMenuScrim != null && quickMenuScrim.getVisibility() == View.VISIBLE;
    }

    public void showQuickMenu() {
        if (quickMenuScrim == null) return;
        rebuildQuickMenuContent();
        quickMenuScrim.setVisibility(View.VISIBLE);
        quickMenuScrim.setAlpha(0f);
        quickMenuScrim.animate().alpha(1f).setDuration(180).start();
        mainHandler.postDelayed(() -> {
            if (quickMenuContainer != null) {
                View first = quickMenuContainer.findFocus();
                if (first == null) {
                    for (int i = 0; i < quickMenuContainer.getChildCount(); i++) {
                        View child = quickMenuContainer.getChildAt(i);
                        if (child.isFocusable()) {
                            child.requestFocus();
                            break;
                        }
                    }
                }
            }
        }, 80);
    }

    public void hideQuickMenu() {
        if (quickMenuScrim == null || quickMenuScrim.getVisibility() != View.VISIBLE) return;
        quickMenuScrim.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            quickMenuScrim.setVisibility(View.GONE);
            if (webView != null) webView.requestFocus();
        }).start();
    }

    private void rebuildQuickMenuContent() {
        if (quickMenuContainer == null) return;
        quickMenuContainer.removeAllViews();

        // 1. Header with App Title & Close instruction
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("VagiiArk TV Settings");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView closeHint = new TextView(this);
        closeHint.setText("Back to Close");
        closeHint.setTextColor(Color.parseColor("#88FFFFFF"));
        closeHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        header.addView(closeHint);

        quickMenuContainer.addView(header);

        // 2. Status & Metrics Header
        statusHeaderView = new TextView(this);
        statusHeaderView.setTextColor(Color.parseColor("#9AA4B2"));
        statusHeaderView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusHeaderView.setPadding(0, dp(6), 0, dp(14));
        updateStatusHeaderText();
        quickMenuContainer.addView(statusHeaderView);

        // 3. Section: AdBlocker
        addSectionHeader("AD BLOCKER");
        addToggleRow(
                "Block YouTube Ads",
                adblockEnabled ? "ACTIVE (0 Ads)" : "DISABLED",
                adblockEnabled,
                () -> {
                    adblockEnabled = !adblockEnabled;
                    prefs.edit().putBoolean("adblock_enabled", adblockEnabled).apply();
                    pushConfigToJs();
                    rebuildQuickMenuContent();
                    showTvToast(adblockEnabled ? "AdBlock enabled!" : "AdBlock disabled", "#00E676");
                }
        );

        // 4. Section: SponsorBlock Master & Per-Category Settings
        addSectionHeader("SPONSORBLOCK CONTROLS");
        addToggleRow(
                "SponsorBlock Master",
                sponsorBlockEnabled ? "ENABLED" : "OFF",
                sponsorBlockEnabled,
                () -> {
                    sponsorBlockEnabled = !sponsorBlockEnabled;
                    prefs.edit().putBoolean("sponsorblock_enabled", sponsorBlockEnabled).apply();
                    pushConfigToJs();
                    rebuildQuickMenuContent();
                    showTvToast(sponsorBlockEnabled ? "SponsorBlock enabled!" : "SponsorBlock off", "#00E676");
                }
        );

        if (sponsorBlockEnabled) {
            TextView catSub = new TextView(this);
            catSub.setText("Click any category below to switch: Auto-Skip | Manual Prompt | Off");
            catSub.setTextColor(Color.parseColor("#78909C"));
            catSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            catSub.setPadding(0, dp(2), 0, dp(8));
            quickMenuContainer.addView(catSub);

            for (String[] catDef : SPONSOR_CATEGORIES) {
                final String catKey = catDef[0];
                final String catLabel = catDef[1];
                final String hexCol = catDef[2];
                final String defMode = catDef[3];
                final String curMode = getCategoryMode(catKey, defMode);

                addCategoryModeRow(catLabel, curMode, hexCol, () -> {
                    String nxt = nextCategoryMode(curMode);
                    setCategoryMode(catKey, nxt);
                    pushConfigToJs();
                    rebuildQuickMenuContent();
                    showTvToast(catLabel + " → " + formatCategoryModeBadge(nxt), hexCol);
                });
            }
        }

        // 5. Section: Playback Speed
        addSectionHeader("PLAYBACK SPEED (" + formatSpeed(playbackSpeed) + ")");
        LinearLayout speedRow = new LinearLayout(this);
        speedRow.setOrientation(LinearLayout.HORIZONTAL);
        speedRow.setGravity(Gravity.CENTER_VERTICAL);
        for (double spd : SPEED_PRESETS) {
            Button btn = createSpeedPillButton(spd, spd == playbackSpeed);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            lp.setMargins(dp(2), 0, dp(2), 0);
            speedRow.addView(btn, lp);
        }
        quickMenuContainer.addView(speedRow);

        // 6. Section: Video Aspect Mode
        addSectionHeader("VIDEO ASPECT RATIO");
        LinearLayout aspectRow = new LinearLayout(this);
        aspectRow.setOrientation(LinearLayout.HORIZONTAL);
        aspectRow.addView(createAspectModeButton("Original 16:9", "fit", "fit".equals(videoAspectMode)), new LinearLayout.LayoutParams(0, dp(34), 1f));
        aspectRow.addView(createAspectModeButton("Zoom to Fill", "fill", "fill".equals(videoAspectMode)), new LinearLayout.LayoutParams(0, dp(34), 1f));
        aspectRow.addView(createAspectModeButton("Stretch", "stretch", "stretch".equals(videoAspectMode)), new LinearLayout.LayoutParams(0, dp(34), 1f));
        quickMenuContainer.addView(aspectRow);

        // 7. Section: App OTA Updates from Vagii-Kavagi/vagiiark-tv
        addSectionHeader("UPDATES & HOTFIXES");
        Button updateBtn = createStyledTvButton(
                "Check GitHub Updates (" + GITHUB_OWNER + "/" + GITHUB_REPO + ")",
                Color.parseColor("#1E2430"),
                Color.parseColor("#448AFF"),
                () -> checkForAppUpdates(true)
        );
        quickMenuContainer.addView(updateBtn);

        // 8. Close Button
        Button closeBtn = createStyledTvButton(
                "Close Settings",
                Color.parseColor("#263238"),
                Color.WHITE,
                this::hideQuickMenu
        );
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)
        );
        closeLp.setMargins(0, dp(18), 0, 0);
        quickMenuContainer.addView(closeBtn, closeLp);
    }

    private void updateStatusHeaderText() {
        if (statusHeaderView == null) return;
        String ver = "v1.7.0";
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = "v" + pInfo.versionName;
        } catch (Exception ignored) {
        }
        statusHeaderView.setText("VagiiArk TV " + ver + "  •  "
                + adsBlockedCount + " Ads Blocked  •  "
                + segmentsSkippedCount + " Segments Skipped (" + (secondsSavedCount / 60) + "m saved)");
    }

    private void addSectionHeader(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(Color.parseColor("#448AFF"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.create("sans-serif-bold", Typeface.BOLD));
        tv.setPadding(0, dp(14), 0, dp(6));
        quickMenuContainer.addView(tv);
    }

    private void addToggleRow(String label, String valueBadge, boolean isChecked, Runnable onToggle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));

        GradientDrawable normalBg = new GradientDrawable();
        normalBg.setColor(Color.parseColor("#161A24"));
        normalBg.setCornerRadius(dp(8));

        GradientDrawable focusBg = new GradientDrawable();
        focusBg.setColor(Color.parseColor("#24314A"));
        focusBg.setCornerRadius(dp(8));
        focusBg.setStroke(dp(2), Color.parseColor("#448AFF"));

        row.setBackground(normalBg);
        row.setOnFocusChangeListener((v, hasFocus) -> row.setBackground(hasFocus ? focusBg : normalBg));
        row.setOnClickListener(v -> onToggle.run());

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(Color.WHITE);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = new TextView(this);
        badge.setText(valueBadge);
        badge.setTextColor(isChecked ? Color.parseColor("#00E676") : Color.parseColor("#FF5252"));
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        badge.setTypeface(Typeface.create("sans-serif-bold", Typeface.BOLD));
        row.addView(badge);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, dp(2), 0, dp(2));
        quickMenuContainer.addView(row, lp);
    }

    private void addCategoryModeRow(String label, String currentMode, String hexAccent, Runnable onCycle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));

        GradientDrawable normalBg = new GradientDrawable();
        normalBg.setColor(Color.parseColor("#151922"));
        normalBg.setCornerRadius(dp(6));

        GradientDrawable focusBg = new GradientDrawable();
        focusBg.setColor(Color.parseColor("#26324D"));
        focusBg.setCornerRadius(dp(6));
        focusBg.setStroke(dp(2), Color.parseColor(hexAccent));

        row.setBackground(normalBg);
        row.setOnFocusChangeListener((v, hasFocus) -> row.setBackground(hasFocus ? focusBg : normalBg));
        row.setOnClickListener(v -> onCycle.run());

        TextView dot = new TextView(this);
        dot.setText("● ");
        try {
            dot.setTextColor(Color.parseColor(hexAccent));
        } catch (Exception e) {
            dot.setTextColor(Color.WHITE);
        }
        dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        row.addView(dot);

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(Color.WHITE);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = new TextView(this);
        badge.setText(formatCategoryModeBadge(currentMode));
        if ("auto".equals(currentMode)) {
            badge.setTextColor(Color.parseColor("#00E676"));
        } else if ("manual".equals(currentMode)) {
            badge.setTextColor(Color.parseColor("#FFD600"));
        } else {
            badge.setTextColor(Color.parseColor("#78909C"));
        }
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        badge.setTypeface(Typeface.create("sans-serif-bold", Typeface.BOLD));
        row.addView(badge);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, dp(2), 0, dp(2));
        quickMenuContainer.addView(row, lp);
    }

    private Button createSpeedPillButton(double spd, boolean isSelected) {
        Button btn = new Button(this);
        btn.setText(formatSpeed(spd));
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btn.setTypeface(Typeface.create("sans-serif-medium", isSelected ? Typeface.BOLD : Typeface.NORMAL));
        btn.setPadding(0, 0, 0, 0);

        GradientDrawable normalBg = new GradientDrawable();
        normalBg.setColor(isSelected ? Color.parseColor("#448AFF") : Color.parseColor("#161A24"));
        normalBg.setCornerRadius(dp(6));

        GradientDrawable focusBg = new GradientDrawable();
        focusBg.setColor(isSelected ? Color.parseColor("#2979FF") : Color.parseColor("#2E3A52"));
        focusBg.setCornerRadius(dp(6));
        focusBg.setStroke(dp(2), Color.WHITE);

        btn.setBackground(normalBg);
        btn.setTextColor(isSelected ? Color.WHITE : Color.parseColor("#B0BEC5"));
        btn.setOnFocusChangeListener((v, hasFocus) -> btn.setBackground(hasFocus ? focusBg : normalBg));

        btn.setOnClickListener(v -> {
            playbackSpeed = spd;
            prefs.edit().putFloat("playback_speed", (float) playbackSpeed).apply();
            pushConfigToJs();
            rebuildQuickMenuContent();
            showTvToast("Speed: " + formatSpeed(spd), "#448AFF");
        });
        return btn;
    }

    private Button createAspectModeButton(String label, String mode, boolean isSelected) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btn.setPadding(0, 0, 0, 0);

        GradientDrawable normalBg = new GradientDrawable();
        normalBg.setColor(isSelected ? Color.parseColor("#00E676") : Color.parseColor("#161A24"));
        normalBg.setCornerRadius(dp(6));

        GradientDrawable focusBg = new GradientDrawable();
        focusBg.setColor(isSelected ? Color.parseColor("#00C853") : Color.parseColor("#2E3A52"));
        focusBg.setCornerRadius(dp(6));
        focusBg.setStroke(dp(2), Color.WHITE);

        btn.setBackground(normalBg);
        btn.setTextColor(isSelected ? Color.BLACK : Color.parseColor("#B0BEC5"));
        btn.setOnFocusChangeListener((v, hasFocus) -> btn.setBackground(hasFocus ? focusBg : normalBg));

        btn.setOnClickListener(v -> {
            videoAspectMode = mode;
            prefs.edit().putString("video_aspect_mode", videoAspectMode).apply();
            pushConfigToJs();
            rebuildQuickMenuContent();
            showTvToast("Aspect: " + label, "#00E676");
        });
        return btn;
    }

    private Button createStyledTvButton(String label, int bgColor, int textColor, Runnable onClick) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextColor(textColor);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        GradientDrawable normalBg = new GradientDrawable();
        normalBg.setColor(bgColor);
        normalBg.setCornerRadius(dp(8));

        GradientDrawable focusBg = new GradientDrawable();
        focusBg.setColor(Color.parseColor("#2C3A56"));
        focusBg.setCornerRadius(dp(8));
        focusBg.setStroke(dp(2), Color.parseColor("#448AFF"));

        btn.setBackground(normalBg);
        btn.setOnFocusChangeListener((v, hasFocus) -> btn.setBackground(hasFocus ? focusBg : normalBg));
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    // ---------------------------------------------------------------------------
    // OTA UPDATE ENGINE (Connects to Vagii-Kavagi/vagiiark-tv Releases)
    // ---------------------------------------------------------------------------
    private void checkForAppUpdates(boolean userInitiated) {
        if (userInitiated) {
            showTvToast("Checking GitHub Releases...", "#448AFF");
        }
        ioExecutor.execute(() -> {
            try {
                URL url = new URL(GITHUB_RELEASES_API);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "VagiiArk-TV-OTA");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONObject release = new JSONObject(sb.toString());
                    String tagName = release.optString("tag_name", "");
                    JSONArray assets = release.optJSONArray("assets");
                    String downloadUrl = null;
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.getJSONObject(i);
                            String name = a.optString("name", "");
                            if (name.endsWith(".apk")) {
                                downloadUrl = a.optString("browser_download_url", null);
                                break;
                            }
                        }
                    }

                    String currentVer = "v1.7.0";
                    try {
                        currentVer = "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                    } catch (Exception ignored) {
                    }
                    final String displayCurrentVer = currentVer;

                    if (!TextUtils.isEmpty(tagName) && !tagName.equalsIgnoreCase(displayCurrentVer) && downloadUrl != null) {
                        final String newVer = tagName;
                        final String finalUrl = downloadUrl;
                        mainHandler.post(() -> promptInstallNewVersion(newVer, finalUrl));
                    } else if (userInitiated) {
                        mainHandler.post(() -> showTvToast("VagiiArk TV is up to date (" + displayCurrentVer + ")", "#00E676"));
                    }
                } else if (userInitiated) {
                    mainHandler.post(() -> showTvToast("No release tag found on GitHub yet", "#FFA000"));
                }
            } catch (Exception e) {
                if (userInitiated) {
                    mainHandler.post(() -> showTvToast("Update check failed (Check network)", "#FF5252"));
                }
            }
        });
    }

    private void promptInstallNewVersion(String newVer, String downloadUrl) {
        showTvToast("Downloading update " + newVer + "...", "#00E676");
        ioExecutor.execute(() -> {
            try {
                URL u = new URL(downloadUrl);
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(30000);
                if (c.getResponseCode() == 200) {
                    File dest = new File(getExternalFilesDir(null), "vagiiark_update.apk");
                    try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(dest)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            out.write(buf, 0, n);
                        }
                    }
                    mainHandler.post(() -> {
                        showTvToast("Installing update " + newVer + "!", "#00E676");
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW);
                            intent.setDataAndType(Uri.fromFile(dest), "application/vnd.android.package-archive");
                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(intent);
                        } catch (Exception e) {
                            showTvToast("Package install prompt opened", "#00E676");
                        }
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> showTvToast("Download failed", "#FF5252"));
            }
        });
    }

    private void syncHotfixFromGitHub(boolean userInitiated) {
        ioExecutor.execute(() -> {
            try {
                URL url = new URL(GITHUB_HOTFIX_JS_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line).append('\n');
                    reader.close();
                    String content = sb.toString();
                    if (!TextUtils.isEmpty(content) && content.contains("__vagiiArkHotfix")) {
                        File patchFile = new File(getFilesDir(), "github_hotfix.js");
                        try (FileOutputStream fos = new FileOutputStream(patchFile)) {
                            fos.write(content.getBytes(StandardCharsets.UTF_8));
                        }
                        mainHandler.post(() -> {
                            loadInjectedScript();
                            injectEngineScripts();
                            if (userInitiated) {
                                showTvToast("Applied latest GitHub hotfix!", "#00E676");
                                if (isQuickMenuOpen()) showQuickMenu();
                            }
                        });
                        return;
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    // ---------------------------------------------------------------------------
    // REMOTE CONTROL KEY EVENTS
    // ---------------------------------------------------------------------------
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        if (isQuickMenuOpen()) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_MENU) {
                if (action == KeyEvent.ACTION_UP) {
                    hideQuickMenu();
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        // Dedicated keys to instantly toggle Quick Menu: MENU, INFO, SETTINGS, GUIDE, PROG_RED/BLUE
        if (keyCode == KeyEvent.KEYCODE_MENU
                || keyCode == KeyEvent.KEYCODE_SETTINGS
                || keyCode == KeyEvent.KEYCODE_INFO
                || keyCode == KeyEvent.KEYCODE_GUIDE
                || keyCode == KeyEvent.KEYCODE_PROG_RED
                || keyCode == KeyEvent.KEYCODE_PROG_BLUE
                || keyCode == KeyEvent.KEYCODE_BOOKMARK) {
            if (action == KeyEvent.ACTION_UP) {
                showQuickMenu();
            }
            return true;
        }

        // Long press D-Pad Center (OK) for 600ms opens Quick Menu
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    okKeyDownTime = System.currentTimeMillis();
                    okLongPressTriggered = false;
                } else if (!okLongPressTriggered && (System.currentTimeMillis() - okKeyDownTime) >= 600) {
                    okLongPressTriggered = true;
                    showQuickMenu();
                    return true;
                }
            } else if (action == KeyEvent.ACTION_UP) {
                if (okLongPressTriggered) {
                    okLongPressTriggered = false;
                    return true;
                }
            }
        }

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (action == KeyEvent.ACTION_UP) {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                    return true;
                }
            }
        }

        return super.dispatchKeyEvent(event);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        );
    }

    private String formatSpeed(double speed) {
        if (speed == (long) speed) {
            return String.format(Locale.US, "%.1fx", speed);
        }
        return String.format(Locale.US, "%.2fx", speed).replace(".00", ".0").replaceFirst("(\\.\\d)0$", "$1");
    }

    // ---------------------------------------------------------------------------
    // JAVASCRIPT BRIDGE INTERFACE
    // ---------------------------------------------------------------------------
    public class VagiiJsBridge {
        @JavascriptInterface
        public String getConfigJson() {
            return buildConfigJson().toString();
        }

        @JavascriptInterface
        public void openQuickMenuFromJs() {
            mainHandler.post(MainActivity.this::showQuickMenu);
        }

        @JavascriptInterface
        public void recordSponsorSkipped(String category, double durationSeconds) {
            segmentsSkippedCount++;
            secondsSavedCount += Math.round(durationSeconds);
            prefs.edit()
                    .putInt("stat_segments_skipped", segmentsSkippedCount)
                    .putLong("stat_seconds_saved", secondsSavedCount)
                    .apply();
        }

        @JavascriptInterface
        public void showToast(String msg, String color) {
            showTvToast(msg, color);
        }
    }
}
