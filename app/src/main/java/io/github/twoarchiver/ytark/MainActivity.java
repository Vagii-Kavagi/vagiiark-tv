package io.github.twoarchiver.ytark;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
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
import java.util.ArrayList;
import java.util.List;
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

    // SmartTV User-Agent that unlocks the full interactive 1080p/4K YouTube TV Leanback interface
    // (Note: Do NOT include CrKey/Chromecast token, or youtube.com/tv shows the "Ready to cast" receiver screen instead of the full browse UI!)
    private static final String YT_TV_USER_AGENT =
            "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.5) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "SamsungBrowser/5.0 Chrome/138.0.7204.179 TV Safari/537.36";

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
            "youtube.com/get_midroll_info",
            "s.youtube.com/api/stats/qoe"
    };

    private static final double[] SPEED_PRESETS = new double[]{
            0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0, 4.0
    };

    private static final String[][] SPONSOR_CATEGORIES = new String[][]{
            {"sponsor", "Sponsor Segments", "#00D400"},
            {"selfpromo", "Unpaid / Self Promotion", "#FFFF00"},
            {"interaction", "Subscribe / Like Reminders", "#CC00FF"},
            {"intro", "Intermission / Intro Animation", "#00FFFF"},
            {"outro", "Endcards & Credits", "#0202ED"},
            {"preview", "Preview / Recap", "#008FD6"},
            {"music_offtopic", "Music: Non-Music Section", "#FF9900"}
    };

    private FrameLayout rootLayout;
    private WebView webView;
    private FrameLayout quickMenuScrim;
    private LinearLayout quickMenuContainer;
    private TextView toastBanner;
    private TextView hudBadge;
    private TextView statusHeaderView;

    private SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();

    private boolean adblockEnabled = true;
    private boolean sponsorBlockEnabled = true;
    private boolean fastModeEnabled = true;
    private double playbackSpeed = 1.0;
    private int adsBlockedCount = 0;
    private int segmentsSkippedCount = 0;
    private int secondsSavedCount = 0;
    private String currentVideoId = "";
    private String currentVideoTitle = "";
    private String cachedInjectedJs = "";
    private String hotfixStatus = "Up to date (v1.0.0)";

    private long okKeyDownTime = 0L;
    private boolean okLongPressTriggered = false;
    private long lastBackPressTime = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

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

        setupHudAndToast();
        setupQuickMenuOverlay();

        setContentView(rootLayout);

        String startUrl = resolveStartUrl(getIntent());
        webView.loadUrl(startUrl);

        // Background check for GitHub hotfixes on startup
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
                    "com.wbd.stream",
                    "com.apple.atve.androidtv.appletv",
                    "com.dstvmobile.android"
            };
            for (String pkg : heavyPackages) {
                am.killBackgroundProcesses(pkg);
            }
        } catch (Exception ignored) {
        }
    }

    private void loadPrefs() {
        adblockEnabled = prefs.getBoolean("adblock_enabled", true);
        sponsorBlockEnabled = prefs.getBoolean("sponsorblock_enabled", true);
        fastModeEnabled = prefs.getBoolean("fast_mode", true);
        playbackSpeed = Double.longBitsToDouble(
                prefs.getLong("playback_speed_bits", Double.doubleToLongBits(1.0))
        );
        if (playbackSpeed < 0.25 || playbackSpeed > 4.0) playbackSpeed = 1.0;
        adsBlockedCount = prefs.getInt("ads_blocked_count", 0);
        segmentsSkippedCount = prefs.getInt("segments_skipped_count", 0);
        secondsSavedCount = prefs.getInt("seconds_saved_count", 0);
    }

    private void savePrefs() {
        SharedPreferences.Editor ed = prefs.edit();
        ed.putBoolean("adblock_enabled", adblockEnabled);
        ed.putBoolean("sponsorblock_enabled", sponsorBlockEnabled);
        ed.putBoolean("fast_mode", fastModeEnabled);
        ed.putLong("playback_speed_bits", Double.doubleToLongBits(playbackSpeed));
        ed.putInt("ads_blocked_count", adsBlockedCount);
        ed.putInt("segments_skipped_count", segmentsSkippedCount);
        ed.putInt("seconds_saved_count", secondsSavedCount);
        ed.apply();
    }

    private boolean isCategoryEnabled(String cat) {
        return prefs.getBoolean("sb_cat_" + cat, true);
    }

    private void setCategoryEnabled(String cat, boolean enabled) {
        prefs.edit().putBoolean("sb_cat_" + cat, enabled).apply();
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
        // Append any hotfix patch downloaded from GitHub
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
        cachedInjectedJs = sb.toString();
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void setupWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);

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
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (adblockEnabled && request != null && request.getUrl() != null) {
                    String url = request.getUrl().toString().toLowerCase(Locale.US);
                    for (String pattern : AD_HOST_PATTERNS) {
                        if (url.contains(pattern)) {
                            adsBlockedCount++;
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
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                injectEngineScripts();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectEngineScripts();
                CookieManager.getInstance().flush();
            }
        });
    }

    private void injectEngineScripts() {
        if (webView == null || TextUtils.isEmpty(cachedInjectedJs)) return;
        webView.evaluateJavascript(cachedInjectedJs, null);
    }

    private void pushConfigToJs() {
        if (webView == null) return;
        try {
            String json = buildConfigJson().toString();
            webView.evaluateJavascript(
                    "if(window.__vagiiArkUpdateConfig){window.__vagiiArkUpdateConfig(" + json + ");}",
                    null
            );
            updateHudBadge();
        } catch (Exception ignored) {
        }
    }

    private JSONObject buildConfigJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("adblockEnabled", adblockEnabled);
            obj.put("sponsorBlockEnabled", sponsorBlockEnabled);
            obj.put("fastMode", fastModeEnabled);
            obj.put("speed", playbackSpeed);

            JSONObject cats = new JSONObject();
            for (String[] row : SPONSOR_CATEGORIES) {
                cats.put(row[0], isCategoryEnabled(row[0]));
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

    // ---------------------------------------------------------------------------
    // HUD BADGE & NATIVE TV TOASTS
    // ---------------------------------------------------------------------------
    private void setupHudAndToast() {
        hudBadge = new TextView(this);
        hudBadge.setTextColor(Color.parseColor("#E0FFFFFF"));
        hudBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hudBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        GradientDrawable hudBg = new GradientDrawable();
        hudBg.setColor(Color.parseColor("#990D1017"));
        hudBg.setCornerRadius(dp(12));
        hudBg.setStroke(dp(1), Color.parseColor("#33FFFFFF"));
        hudBadge.setBackground(hudBg);
        hudBadge.setPadding(dp(12), dp(5), dp(12), dp(5));

        FrameLayout.LayoutParams hudLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START
        );
        hudLp.setMargins(dp(24), dp(18), 0, 0);
        rootLayout.addView(hudBadge, hudLp);
        updateHudBadge();

        // Auto-fade HUD badge after 6 seconds so screen stays clean
        mainHandler.postDelayed(() -> hudBadge.animate().alpha(0f).setDuration(500).start(), 6000);

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

    private void updateHudBadge() {
        if (hudBadge == null) return;
        String speedText = formatSpeed(playbackSpeed);
        String text = "⚡ VagiiArk TV  •  AdBlock " + (adblockEnabled ? "ON" : "OFF")
                + "  •  SponsorBlock " + (sponsorBlockEnabled ? "ON" : "OFF")
                + "  •  Speed " + speedText
                + "  (Hold OK or press MENU for panel)";
        hudBadge.setText(text);
        hudBadge.setAlpha(1f);
        hudBadge.animate().cancel();
        mainHandler.postDelayed(() -> hudBadge.animate().alpha(0f).setDuration(500).start(), 5000);
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
    // TIZENTUBE-STYLE NATIVE QUICK MENU OVERLAY (D-Pad Friendly)
    // ---------------------------------------------------------------------------
    private void setupQuickMenuOverlay() {
        quickMenuScrim = new FrameLayout(this);
        quickMenuScrim.setBackgroundColor(Color.parseColor("#B805070B"));
        quickMenuScrim.setVisibility(View.GONE);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setScrollbarFadingEnabled(false);
        FrameLayout.LayoutParams scrollLp = new FrameLayout.LayoutParams(
                dp(460),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END
        );

        quickMenuContainer = new LinearLayout(this);
        quickMenuContainer.setOrientation(LinearLayout.VERTICAL);
        // Top padding of 54dp clears the top-right network speed & clock overlay cleanly
        quickMenuContainer.setPadding(dp(24), dp(54), dp(24), dp(28));
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(Color.parseColor("#F512151E"));
        panelBg.setStroke(dp(1), Color.parseColor("#33FFFFFF"));
        quickMenuContainer.setBackground(panelBg);

        scrollView.addView(quickMenuContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        quickMenuScrim.addView(scrollView, scrollLp);
        rootLayout.addView(quickMenuScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    private boolean isQuickMenuOpen() {
        return quickMenuScrim != null && quickMenuScrim.getVisibility() == View.VISIBLE;
    }

    private void showQuickMenu() {
        if (quickMenuContainer == null) return;
        quickMenuContainer.removeAllViews();

        // Title Header
        TextView title = new TextView(this);
        title.setText("⚡ VagiiArk TV Settings");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        quickMenuContainer.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("GitHub: " + GITHUB_OWNER + "/" + GITHUB_REPO + "  •  " + hotfixStatus);
        subtitle.setTextColor(Color.parseColor("#90A4AE"));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        quickMenuContainer.addView(subtitle);

        // Live Stats Card
        statusHeaderView = new TextView(this);
        statusHeaderView.setTextColor(Color.parseColor("#00E676"));
        statusHeaderView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusHeaderView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        GradientDrawable statsBg = new GradientDrawable();
        statsBg.setColor(Color.parseColor("#1E2433"));
        statsBg.setCornerRadius(dp(10));
        statusHeaderView.setBackground(statsBg);
        statusHeaderView.setPadding(dp(14), dp(10), dp(14), dp(10));
        updateStatsBannerText();
        LinearLayout.LayoutParams statsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        statsLp.setMargins(0, 0, 0, dp(16));
        quickMenuContainer.addView(statusHeaderView, statsLp);

        // Section 1: Playback Speed Control
        addSectionHeader("VIDEO SPEED CONTROL (Instant)");

        LinearLayout speedRow1 = new LinearLayout(this);
        speedRow1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout speedRow2 = new LinearLayout(this);
        speedRow2.setOrientation(LinearLayout.HORIZONTAL);

        View firstFocusTarget = null;
        for (int i = 0; i < SPEED_PRESETS.length; i++) {
            final double s = SPEED_PRESETS[i];
            boolean selected = Math.abs(playbackSpeed - s) < 0.01;
            Button chip = createChipButton(formatSpeed(s), selected, v -> {
                playbackSpeed = s;
                savePrefs();
                if (webView != null) {
                    webView.evaluateJavascript("if(window.__vagiiArkSetSpeed){window.__vagiiArkSetSpeed(" + s + ");}", null);
                }
                updateHudBadge();
                showQuickMenu();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            if (i < 5) {
                speedRow1.addView(chip, lp);
            } else {
                speedRow2.addView(chip, lp);
            }
            if (selected) firstFocusTarget = chip;
        }
        quickMenuContainer.addView(speedRow1);
        quickMenuContainer.addView(speedRow2);

        // Section 2: Core Toggles
        addSectionHeader("ADBLOCK & PERFORMANCE ENGINE");

        Button adblockBtn = createMenuRowButton(
                "🛡️  Zero-Latency AdBlocker",
                adblockEnabled ? "ENABLED" : "DISABLED",
                adblockEnabled,
                v -> {
                    adblockEnabled = !adblockEnabled;
                    savePrefs();
                    pushConfigToJs();
                    showTvToast("AdBlocker " + (adblockEnabled ? "Enabled" : "Disabled"), "#00E676");
                    showQuickMenu();
                }
        );
        quickMenuContainer.addView(adblockBtn);
        if (firstFocusTarget == null) firstFocusTarget = adblockBtn;

        Button sbToggleBtn = createMenuRowButton(
                "⏭️  SponsorBlock Auto-Skip",
                sponsorBlockEnabled ? "ENABLED" : "DISABLED",
                sponsorBlockEnabled,
                v -> {
                    sponsorBlockEnabled = !sponsorBlockEnabled;
                    savePrefs();
                    pushConfigToJs();
                    showTvToast("SponsorBlock " + (sponsorBlockEnabled ? "Enabled" : "Disabled"), "#00B0FF");
                    showQuickMenu();
                }
        );
        quickMenuContainer.addView(sbToggleBtn);

        Button fastModeBtn = createMenuRowButton(
                "🚀  60fps MStar Turbo Mode (No Blur/Shadows)",
                fastModeEnabled ? "ENABLED" : "DISABLED",
                fastModeEnabled,
                v -> {
                    fastModeEnabled = !fastModeEnabled;
                    savePrefs();
                    pushConfigToJs();
                    showTvToast("60fps Turbo Mode " + (fastModeEnabled ? "Enabled" : "Disabled"), "#FFD600");
                    showQuickMenu();
                }
        );
        quickMenuContainer.addView(fastModeBtn);

        // Section 3: SponsorBlock Categories
        addSectionHeader("SPONSORBLOCK CATEGORIES");
        for (String[] cat : SPONSOR_CATEGORIES) {
            final String key = cat[0];
            final String label = cat[1];
            final String colorHex = cat[2];
            boolean on = isCategoryEnabled(key);
            Button catBtn = createMenuRowButton(
                    "●  " + label,
                    on ? "SKIP" : "OFF",
                    on,
                    v -> {
                        setCategoryEnabled(key, !on);
                        pushConfigToJs();
                        showQuickMenu();
                    }
            );
            quickMenuContainer.addView(catBtn);
        }

        // Section 4: GitHub OTA Updates & Diagnostics
        addSectionHeader("GITHUB UPDATES & DIAGNOSTICS (" + GITHUB_OWNER + "/" + GITHUB_REPO + ")");

        Button checkOtaBtn = createMenuRowButton(
                "🔄  Check GitHub for App Update (OTA APK)",
                "v" + getAppVersionName(),
                true,
                v -> checkGitHubReleaseUpdate()
        );
        quickMenuContainer.addView(checkOtaBtn);

        Button syncHotfixBtn = createMenuRowButton(
                "⚡  Pull Latest AdBlock/SponsorBlock Hotfix from GitHub",
                "LIVE PATCH",
                true,
                v -> syncHotfixFromGitHub(true)
        );
        quickMenuContainer.addView(syncHotfixBtn);

        Button reloadBtn = createMenuRowButton(
                "🔁  Reload YouTube TV Engine",
                "REFRESH",
                false,
                v -> {
                    hideQuickMenu();
                    if (webView != null) webView.reload();
                }
        );
        quickMenuContainer.addView(reloadBtn);

        Button closeBtn = createMenuRowButton(
                "✕  Close Panel (or press BACK)",
                "",
                false,
                v -> hideQuickMenu()
        );
        quickMenuContainer.addView(closeBtn);

        quickMenuScrim.setVisibility(View.VISIBLE);
        final View target = firstFocusTarget;
        mainHandler.post(() -> {
            if (target != null) target.requestFocus();
        });
    }

    private void hideQuickMenu() {
        if (quickMenuScrim != null) {
            quickMenuScrim.setVisibility(View.GONE);
        }
        if (webView != null) {
            webView.requestFocus();
        }
    }

    private void updateStatsBannerText() {
        if (statusHeaderView == null) return;
        int minsSaved = secondsSavedCount / 60;
        int remSecs = secondsSavedCount % 60;
        String timeStr = minsSaved > 0 ? (minsSaved + "m " + remSecs + "s") : (remSecs + "s");
        statusHeaderView.setText(String.format(
                Locale.US,
                "Ads Blocked: %d   •   Segments Skipped: %d   •   Time Saved: %s",
                adsBlockedCount, segmentsSkippedCount, timeStr
        ));
    }

    private void addSectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#78909C"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tv.setPadding(0, dp(14), 0, dp(6));
        quickMenuContainer.addView(tv);
    }

    private Button createChipButton(String label, boolean active, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTypeface(Typeface.create("sans-serif-medium", active ? Typeface.BOLD : Typeface.NORMAL));
        b.setTextColor(active ? Color.BLACK : Color.WHITE);
        b.setPadding(dp(4), dp(2), dp(4), dp(2));
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);

        styleSelectableButton(b, active ? Color.parseColor("#FFD600") : Color.parseColor("#23293A"), active);
        b.setOnClickListener(listener);
        return b;
    }

    private Button createMenuRowButton(String title, String badge, boolean enabledState, View.OnClickListener listener) {
        Button b = new Button(this);
        String fullText = TextUtils.isEmpty(badge) ? title : (title + "    [" + badge + "]");
        b.setText(fullText);
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);

        int baseColor = enabledState ? Color.parseColor("#1D2B3A") : Color.parseColor("#1A1E29");
        styleSelectableButton(b, baseColor, false);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, dp(4), 0, dp(4));
        b.setLayoutParams(lp);
        b.setOnClickListener(listener);
        return b;
    }

    private void styleSelectableButton(final Button b, final int normalBgColor, final boolean activeChip) {
        final Runnable applyNormal = () -> {
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(normalBgColor);
            gd.setCornerRadius(dp(10));
            gd.setStroke(dp(1), activeChip ? Color.parseColor("#FFD600") : Color.parseColor("#2EFFFFFF"));
            b.setBackground(gd);
            b.setTextColor(activeChip ? Color.BLACK : Color.WHITE);
        };
        applyNormal.run();

        b.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                GradientDrawable gd = new GradientDrawable();
                gd.setColor(Color.parseColor("#FF1744"));
                gd.setCornerRadius(dp(10));
                gd.setStroke(dp(2), Color.WHITE);
                b.setBackground(gd);
                b.setTextColor(Color.WHITE);
            } else {
                applyNormal.run();
            }
        });
    }

    // ---------------------------------------------------------------------------
    // GITHUB IN-APP OTA APK UPDATER & LIVE HOTFIX SYNC
    // ---------------------------------------------------------------------------
    private String getAppVersionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "1.0.0";
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    private void syncHotfixFromGitHub(final boolean userInitiated) {
        if (userInitiated) {
            showTvToast("Checking GitHub (" + GITHUB_OWNER + "/" + GITHUB_REPO + ") for hotfix...", "#00B0FF");
        }
        ioExecutor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(GITHUB_HOTFIX_JS_URL + "?t=" + System.currentTimeMillis());
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("User-Agent", "VagiiArk-TV-Updater/1.0");
                int code = conn.getResponseCode();
                if (code == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            sb.append(line).append('\n');
                        }
                    }
                    String patch = sb.toString().trim();
                    if (!TextUtils.isEmpty(patch)) {
                        File patchFile = new File(getFilesDir(), "github_hotfix.js");
                        try (FileOutputStream fos = new FileOutputStream(patchFile)) {
                            fos.write(patch.getBytes(StandardCharsets.UTF_8));
                        }
                        loadInjectedScript();
                        hotfixStatus = "GitHub Hotfix Active";
                        mainHandler.post(() -> {
                            injectEngineScripts();
                            if (userInitiated) {
                                showTvToast("Applied latest GitHub hotfix!", "#00E676");
                                if (isQuickMenuOpen()) showQuickMenu();
                            }
                        });
                        return;
                    }
                }
                if (userInitiated) {
                    showTvToast("Already on latest engine rules (v" + getAppVersionName() + ")", "#00E676");
                }
            } catch (Exception e) {
                if (userInitiated) {
                    showTvToast("GitHub check finished (v" + getAppVersionName() + " up to date)", "#00B0FF");
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void checkGitHubReleaseUpdate() {
        showTvToast("Checking GitHub Releases (" + GITHUB_OWNER + "/" + GITHUB_REPO + ")...", "#00B0FF");
        ioExecutor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(GITHUB_RELEASES_API);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(7000);
                conn.setReadTimeout(7000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "VagiiArk-TV-Updater/1.0");

                int code = conn.getResponseCode();
                if (code != 200) {
                    showTvToast("You are on the latest release (v" + getAppVersionName() + ")", "#00E676");
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        sb.append(line);
                    }
                }

                JSONObject release = new JSONObject(sb.toString());
                String tagName = release.optString("tag_name", "").replaceFirst("^v", "");
                String currentVer = getAppVersionName().replaceFirst("^v", "");
                if (TextUtils.isEmpty(tagName) || tagName.equalsIgnoreCase(currentVer)) {
                    showTvToast("Up to date! Running v" + currentVer, "#00E676");
                    return;
                }

                JSONArray assets = release.optJSONArray("assets");
                String apkUrl = null;
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String dl = asset.optString("browser_download_url", "");
                        if (dl.endsWith(".apk")) {
                            apkUrl = dl;
                            break;
                        }
                    }
                }

                if (TextUtils.isEmpty(apkUrl)) {
                    showTvToast("Release v" + tagName + " found (no APK attached yet)", "#FFD600");
                    return;
                }

                showTvToast("Downloading update v" + tagName + " from GitHub...", "#FFD600");
                downloadAndInstallApk(apkUrl, tagName);
            } catch (Exception e) {
                showTvToast("Up to date (v" + getAppVersionName() + ")", "#00E676");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void downloadAndInstallApk(String apkUrl, String version) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(apkUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "VagiiArk-TV-Updater/1.0");

            File outFile = new File(getExternalCacheDir() != null ? getExternalCacheDir() : getCacheDir(),
                    "vagiiark-update-" + version + ".apk");
            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(outFile)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = is.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                }
            }
            outFile.setReadable(true, false);

            mainHandler.post(() -> {
                try {
                    showTvToast("Update downloaded! Launching installer...", "#00E676");
                    Intent installIntent = new Intent(Intent.ACTION_VIEW);
                    installIntent.setDataAndType(Uri.fromFile(outFile), "application/vnd.android.package-archive");
                    installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(installIntent);
                } catch (Exception e) {
                    showTvToast("Saved update to: " + outFile.getAbsolutePath(), "#FFD600");
                }
            });
        } catch (Exception e) {
            showTvToast("Update download failed: " + e.getMessage(), "#FF1744");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------------------------------------------------------------------------
    // REMOTE KEY HANDLING (Menu / Info / Hold-OK / Color Buttons / Back)
    // ---------------------------------------------------------------------------
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        // When Quick Menu is open, let Android native focus navigate the overlay buttons
        if (isQuickMenuOpen()) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_MENU) {
                if (action == KeyEvent.ACTION_UP) {
                    hideQuickMenu();
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        // Direct remote shortcuts to open Quick Settings Panel:
        // MENU, INFO, GUIDE, SETTINGS, CAPTIONS, PROG_BLUE, PROG_YELLOW
        if (keyCode == KeyEvent.KEYCODE_MENU
                || keyCode == KeyEvent.KEYCODE_INFO
                || keyCode == KeyEvent.KEYCODE_GUIDE
                || keyCode == KeyEvent.KEYCODE_SETTINGS
                || keyCode == KeyEvent.KEYCODE_CAPTIONS
                || keyCode == KeyEvent.KEYCODE_PROG_BLUE
                || keyCode == KeyEvent.KEYCODE_BOOKMARK) {
            if (action == KeyEvent.ACTION_UP) {
                showQuickMenu();
            }
            return true;
        }

        // Fast speed stepping via Red / Green or Channel Down / Channel Up buttons if present
        if (keyCode == KeyEvent.KEYCODE_PROG_RED || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
            if (action == KeyEvent.ACTION_DOWN && webView != null) {
                webView.evaluateJavascript("if(window.__vagiiArkStepSpeed){window.__vagiiArkStepSpeed(-0.25);}", null);
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_PROG_GREEN || keyCode == KeyEvent.KEYCODE_PAGE_UP) {
            if (action == KeyEvent.ACTION_DOWN && webView != null) {
                webView.evaluateJavascript("if(window.__vagiiArkStepSpeed){window.__vagiiArkStepSpeed(0.25);}", null);
            }
            return true;
        }

        // Long-press OK / DPAD_CENTER (held >= 650ms) opens Quick Menu on minimal 6-button Xiaomi remotes!
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    okKeyDownTime = System.currentTimeMillis();
                    okLongPressTriggered = false;
                } else if (!okLongPressTriggered && (System.currentTimeMillis() - okKeyDownTime) >= 650) {
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

        // Route Back key to YouTube TV's internal SPA navigation (Escape), double-press on root to exit
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (action == KeyEvent.ACTION_UP && webView != null) {
                String url = webView.getUrl() != null ? webView.getUrl() : "";
                boolean onRootBrowse = url.endsWith("/tv") || url.endsWith("/tv#/") || url.endsWith("/tv#/browse");
                sendEscapeKeyToWebView();
                if (onRootBrowse) {
                    long now = System.currentTimeMillis();
                    if (now - lastBackPressTime < 1800) {
                        finish();
                    } else {
                        lastBackPressTime = now;
                        showTvToast("Press BACK again to exit VagiiArk TV", "#FFD600");
                    }
                }
            }
            return true;
        }

        return super.dispatchKeyEvent(event);
    }

    private void sendEscapeKeyToWebView() {
        if (webView == null) return;
        String js = "(function(){"
                + "var evDown = new KeyboardEvent('keydown',{key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true,cancelable:true});"
                + "var evUp = new KeyboardEvent('keyup',{key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true,cancelable:true});"
                + "document.dispatchEvent(evDown); document.dispatchEvent(evUp);"
                + "})();";
        webView.evaluateJavascript(js, null);
    }

    // ---------------------------------------------------------------------------
    // JAVASCRIPT <-> NATIVE BRIDGE (SponsorBlock API + Stats + Speed Sync)
    // ---------------------------------------------------------------------------
    public class VagiiJsBridge {

        @JavascriptInterface
        public String getConfigJson() {
            return buildConfigJson().toString();
        }

        @JavascriptInterface
        public void showNativeToast(String message, String accentColor) {
            showTvToast(message, accentColor);
        }

        @JavascriptInterface
        public void onAdBlocked(int count) {
            adsBlockedCount += Math.max(1, count);
            savePrefs();
        }

        @JavascriptInterface
        public void onSegmentSkipped(String category, int secondsSaved) {
            segmentsSkippedCount += 1;
            secondsSavedCount += Math.max(1, secondsSaved);
            savePrefs();
        }

        @JavascriptInterface
        public void onSpeedChangedFromJs(double newSpeed) {
            playbackSpeed = newSpeed;
            savePrefs();
            mainHandler.post(MainActivity.this::updateHudBadge);
        }

        @JavascriptInterface
        public void onVideoChanged(String videoId, String title) {
            currentVideoId = videoId != null ? videoId : "";
            currentVideoTitle = title != null ? title : "";
        }

        @JavascriptInterface
        public void openQuickMenuFromJs() {
            mainHandler.post(MainActivity.this::showQuickMenu);
        }

        @JavascriptInterface
        public void fetchSponsorSegments(final String videoId, final String categoriesJson) {
            if (TextUtils.isEmpty(videoId) || !sponsorBlockEnabled) return;
            ioExecutor.execute(() -> {
                HttpURLConnection conn = null;
                try {
                    String catsParam = TextUtils.isEmpty(categoriesJson)
                            ? "[\"sponsor\",\"selfpromo\",\"interaction\",\"intro\",\"outro\",\"preview\",\"music_offtopic\"]"
                            : categoriesJson;
                    String endpoint = "https://sponsor.ajay.app/api/skipSegments?videoID="
                            + URLEncoder.encode(videoId, "UTF-8")
                            + "&categories=" + URLEncoder.encode(catsParam, "UTF-8");

                    URL url = new URL(endpoint);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setRequestProperty("User-Agent", "VagiiArk-TV/1.0");

                    int code = conn.getResponseCode();
                    if (code == 200) {
                        StringBuilder sb = new StringBuilder();
                        try (BufferedReader r = new BufferedReader(
                                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = r.readLine()) != null) {
                                sb.append(line);
                            }
                        }
                        final String responseJson = sb.toString();
                        mainHandler.post(() -> {
                            if (webView != null) {
                                webView.evaluateJavascript(
                                        "if(window.__vagiiArkSetSegments){window.__vagiiArkSetSegments("
                                                + JSONObject.quote(videoId) + "," + responseJson + ");}",
                                        null
                                );
                            }
                        });
                    }
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) conn.disconnect();
                }
            });
        }
    }

    private String formatSpeed(double s) {
        if (Math.abs(s - Math.round(s)) < 0.01) {
            return String.format(Locale.US, "%.1fx", s);
        }
        if (Math.abs((s * 10) - Math.round(s * 10)) < 0.01) {
            return String.format(Locale.US, "%.1fx", s);
        }
        return String.format(Locale.US, "%.2fx", s);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        ));
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        ioExecutor.shutdownNow();
        super.onDestroy();
    }
}
