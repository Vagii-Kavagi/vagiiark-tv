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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private TextView statsBadgeCard;

    private SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();

    private boolean adblockEnabled = true;
    private boolean blockPromoPopups = true;
    private boolean sponsorBlockEnabled = true;
    private boolean showSkipNotice = true;
    private String preferredQuality = "1080"; // "1080", "720", "auto"
    private double playbackSpeed = 1.0;
    private String videoAspectMode = "fit"; // "fit" (Default 1:1), "fill", "stretch"
    private boolean fastMode = true;

    private int adsBlockedCount = 0;
    private int segmentsSkippedCount = 0;
    private long secondsSavedCount = 0;

    private String injectedScriptContent = "";
    private long okKeyDownTime = 0;
    private boolean okLongPressTriggered = false;

    // Direct UI references for in-place state updating (ZERO focus loss!)
    private ToggleRowView adblockRow;
    private ToggleRowView promoPopupsRow;
    private ToggleRowView sponsorMasterRow;
    private ToggleRowView skipNoticeRow;
    private final Map<String, CategoryModeRowView> categoryRows = new HashMap<>();
    private final List<Button> speedButtons = new ArrayList<>();
    private final List<Button> aspectButtons = new ArrayList<>();
    private final List<Button> qualityButtons = new ArrayList<>();

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
        blockPromoPopups = prefs.getBoolean("block_promo_popups", true);
        sponsorBlockEnabled = prefs.getBoolean("sponsorblock_enabled", true);
        showSkipNotice = prefs.getBoolean("show_skip_notice", true);
        preferredQuality = prefs.getString("preferred_quality", "1080");
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
        if ("manual".equals(mode)) return "MANUAL PROMPT";
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
            obj.put("blockPromoPopups", blockPromoPopups);
            obj.put("sponsorBlockEnabled", sponsorBlockEnabled);
            obj.put("showSkipNotice", showSkipNotice);
            obj.put("preferredQuality", preferredQuality);
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
        mainHandler.post(this::updateStatsBadge);
    }

    private void setupToastBanner() {
        toastBanner = new TextView(this);
        toastBanner.setTextColor(Color.WHITE);
        toastBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        toastBanner.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        toastBanner.setPadding(dp(20), dp(12), dp(20), dp(12));
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
            bg.setColor(Color.parseColor("#E60B0E18"));
            bg.setCornerRadius(dp(16));
            bg.setStroke(dp(2), accent);
            toastBanner.setBackground(bg);
            toastBanner.setText(message);
            toastBanner.animate().cancel();
            toastBanner.setAlpha(1f);
            mainHandler.removeCallbacks(hideToastRunnable);
            mainHandler.postDelayed(hideToastRunnable, 2800);
        });
    }

    private final Runnable hideToastRunnable = () -> {
        if (toastBanner != null) {
            toastBanner.animate().alpha(0f).setDuration(250).start();
        }
    };

    // ---------------------------------------------------------------------------
    // STYLISH, MODERN IN-APP SETTINGS MENU (Glass UI + Zero Focus-Loss State Updates!)
    // ---------------------------------------------------------------------------
    private void setupQuickMenuOverlay() {
        quickMenuScrim = new FrameLayout(this);
        quickMenuScrim.setBackgroundColor(Color.parseColor("#C804070D"));
        quickMenuScrim.setVisibility(View.GONE);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setScrollbarFadingEnabled(false);
        FrameLayout.LayoutParams scrollLp = new FrameLayout.LayoutParams(
                dp(520),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END
        );
        quickMenuScrim.addView(scrollView, scrollLp);

        quickMenuContainer = new LinearLayout(this);
        quickMenuContainer.setOrientation(LinearLayout.VERTICAL);
        quickMenuContainer.setPadding(dp(28), dp(40), dp(28), dp(36));

        // Modern Frosted Glass Dark Card with electric cyan border accent
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(Color.parseColor("#F20B0F19"));
        panelBg.setStroke(dp(1), Color.parseColor("#29487D"));
        quickMenuContainer.setBackground(panelBg);

        scrollView.addView(quickMenuContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        rootLayout.addView(quickMenuScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        buildMenuViewsOnce();
    }

    public boolean isQuickMenuOpen() {
        return quickMenuScrim != null && quickMenuScrim.getVisibility() == View.VISIBLE;
    }

    public void showQuickMenu() {
        if (quickMenuScrim == null) return;
        updateAllMenuStateViews();
        quickMenuScrim.setVisibility(View.VISIBLE);
        quickMenuScrim.setAlpha(0f);
        quickMenuScrim.animate().alpha(1f).setDuration(160).start();
        mainHandler.postDelayed(() -> {
            if (adblockRow != null) {
                adblockRow.requestFocus();
            }
        }, 60);
    }

    public void hideQuickMenu() {
        if (quickMenuScrim == null || quickMenuScrim.getVisibility() != View.VISIBLE) return;
        quickMenuScrim.animate().alpha(0f).setDuration(140).withEndAction(() -> {
            quickMenuScrim.setVisibility(View.GONE);
            if (webView != null) webView.requestFocus();
        }).start();
    }

    private void buildMenuViewsOnce() {
        quickMenuContainer.removeAllViews();
        categoryRows.clear();
        speedButtons.clear();
        aspectButtons.clear();
        qualityButtons.clear();

        // 1. Top Header with Glowing Title & Version Pill
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("⚡ VAGIIARK TV");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView verBadge = new TextView(this);
        verBadge.setText("v1.7.1");
        verBadge.setTextColor(Color.parseColor("#00E5FF"));
        verBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        verBadge.setTypeface(Typeface.create("sans-serif-bold", Typeface.BOLD));
        verBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable vBg = new GradientDrawable();
        vBg.setColor(Color.parseColor("#182A4A"));
        vBg.setCornerRadius(dp(12));
        verBadge.setBackground(vBg);
        header.addView(verBadge);

        quickMenuContainer.addView(header);

        // 2. Metrics & Shield Stats Card
        statsBadgeCard = new TextView(this);
        statsBadgeCard.setTextColor(Color.parseColor("#A8B8D0"));
        statsBadgeCard.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statsBadgeCard.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable statsBg = new GradientDrawable();
        statsBg.setColor(Color.parseColor("#131926"));
        statsBg.setCornerRadius(dp(10));
        statsBg.setStroke(dp(1), Color.parseColor("#1E2D48"));
        statsBadgeCard.setBackground(statsBg);

        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.setMargins(0, dp(12), 0, dp(14));
        quickMenuContainer.addView(statsBadgeCard, sLp);

        // 3. Section: SHIELD & PRIVACY
        addSectionHeader("🛡 AD & CONTENT SHIELD");
        adblockRow = new ToggleRowView(this, "Block Video Ads", adblockEnabled, (row) -> {
            adblockEnabled = !adblockEnabled;
            prefs.edit().putBoolean("adblock_enabled", adblockEnabled).apply();
            pushConfigToJs();
            row.setChecked(adblockEnabled, adblockEnabled ? "ACTIVE (" + adsBlockedCount + " Ads)" : "DISABLED");
            showTvToast(adblockEnabled ? "AdBlock enabled!" : "AdBlock disabled", "#00E676");
        });
        quickMenuContainer.addView(adblockRow);

        promoPopupsRow = new ToggleRowView(this, "Block Promo Popups & Surveys", blockPromoPopups, (row) -> {
            blockPromoPopups = !blockPromoPopups;
            prefs.edit().putBoolean("block_promo_popups", blockPromoPopups).apply();
            pushConfigToJs();
            row.setChecked(blockPromoPopups, blockPromoPopups ? "ENABLED" : "OFF");
            showTvToast(blockPromoPopups ? "Promo popups blocked!" : "Promo popups allowed", "#00E676");
        });
        quickMenuContainer.addView(promoPopupsRow);

        // 4. Section: SPONSORBLOCK STUDIO
        addSectionHeader("⚡ SPONSORBLOCK STUDIO");
        sponsorMasterRow = new ToggleRowView(this, "SponsorBlock Master Engine", sponsorBlockEnabled, (row) -> {
            sponsorBlockEnabled = !sponsorBlockEnabled;
            prefs.edit().putBoolean("sponsorblock_enabled", sponsorBlockEnabled).apply();
            pushConfigToJs();
            row.setChecked(sponsorBlockEnabled, sponsorBlockEnabled ? "ACTIVE" : "OFF");
            showTvToast(sponsorBlockEnabled ? "SponsorBlock enabled!" : "SponsorBlock disabled", "#00E676");
        });
        quickMenuContainer.addView(sponsorMasterRow);

        skipNoticeRow = new ToggleRowView(this, "Show Skip Toast Notice", showSkipNotice, (row) -> {
            showSkipNotice = !showSkipNotice;
            prefs.edit().putBoolean("show_skip_notice", showSkipNotice).apply();
            pushConfigToJs();
            row.setChecked(showSkipNotice, showSkipNotice ? "ENABLED" : "OFF");
            showTvToast(showSkipNotice ? "Skip notices on" : "Skip notices off", "#00E676");
        });
        quickMenuContainer.addView(skipNoticeRow);

        TextView catSub = new TextView(this);
        catSub.setText("Per-Category Modes (Click to switch: Auto-Skip | Manual Prompt | Off)");
        catSub.setTextColor(Color.parseColor("#78909C"));
        catSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        catSub.setPadding(0, dp(6), 0, dp(4));
        quickMenuContainer.addView(catSub);

        for (String[] catDef : SPONSOR_CATEGORIES) {
            final String catKey = catDef[0];
            final String catLabel = catDef[1];
            final String hexCol = catDef[2];
            final String defMode = catDef[3];
            final String curMode = getCategoryMode(catKey, defMode);

            CategoryModeRowView catRow = new CategoryModeRowView(this, catLabel, curMode, hexCol, (row) -> {
                String nxt = nextCategoryMode(row.getCurrentMode());
                setCategoryMode(catKey, nxt);
                row.setMode(nxt);
                pushConfigToJs();
                showTvToast(catLabel + " → " + formatCategoryModeBadge(nxt), hexCol);
            });
            categoryRows.put(catKey, catRow);
            quickMenuContainer.addView(catRow);
        }

        // 5. Section: PLAYBACK & VIDEO ENGINE
        addSectionHeader("🎬 PLAYBACK & VIDEO ENGINE");

        // Quality selection row
        TextView qLbl = new TextView(this);
        qLbl.setText("Preferred Default Quality:");
        qLbl.setTextColor(Color.parseColor("#B0BEC5"));
        qLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        qLbl.setPadding(0, dp(2), 0, dp(4));
        quickMenuContainer.addView(qLbl);

        LinearLayout qRow = new LinearLayout(this);
        qRow.setOrientation(LinearLayout.HORIZONTAL);
        addQualityButton(qRow, "1080p (Default)", "1080");
        addQualityButton(qRow, "720p", "720");
        addQualityButton(qRow, "Auto", "auto");
        quickMenuContainer.addView(qRow);

        // Speed row
        TextView spdLbl = new TextView(this);
        spdLbl.setText("Playback Speed:");
        spdLbl.setTextColor(Color.parseColor("#B0BEC5"));
        spdLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        spdLbl.setPadding(0, dp(8), 0, dp(4));
        quickMenuContainer.addView(spdLbl);

        LinearLayout speedRow = new LinearLayout(this);
        speedRow.setOrientation(LinearLayout.HORIZONTAL);
        for (double spd : SPEED_PRESETS) {
            Button btn = createSpeedButton(spd);
            speedButtons.add(btn);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            lp.setMargins(dp(2), 0, dp(2), 0);
            speedRow.addView(btn, lp);
        }
        quickMenuContainer.addView(speedRow);

        // Aspect row
        TextView aspLbl = new TextView(this);
        aspLbl.setText("Video Aspect Mode:");
        aspLbl.setTextColor(Color.parseColor("#B0BEC5"));
        aspLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        aspLbl.setPadding(0, dp(8), 0, dp(4));
        quickMenuContainer.addView(aspLbl);

        LinearLayout aspectRow = new LinearLayout(this);
        aspectRow.setOrientation(LinearLayout.HORIZONTAL);
        addAspectButton(aspectRow, "Original 16:9", "fit");
        addAspectButton(aspectRow, "Zoom to Fill", "fill");
        addAspectButton(aspectRow, "Stretch", "stretch");
        quickMenuContainer.addView(aspectRow);

        // 6. Section: SYSTEM & UPDATES
        addSectionHeader("🚀 SYSTEM & OTA UPDATES");
        Button updateBtn = createStyledTvButton(
                "Check GitHub Updates (" + GITHUB_OWNER + "/" + GITHUB_REPO + ")",
                Color.parseColor("#152033"),
                Color.parseColor("#448AFF"),
                () -> checkForAppUpdates(true)
        );
        quickMenuContainer.addView(updateBtn);

        Button resetBtn = createStyledTvButton(
                "Reset All Settings to Defaults",
                Color.parseColor("#1D1822"),
                Color.parseColor("#FF5252"),
                this::resetSettingsToDefault
        );
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        rLp.setMargins(0, dp(6), 0, 0);
        quickMenuContainer.addView(resetBtn, rLp);

        // Close Button
        Button closeBtn = createStyledTvButton(
                "Close Settings (Back)",
                Color.parseColor("#1B2230"),
                Color.WHITE,
                this::hideQuickMenu
        );
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        closeLp.setMargins(0, dp(18), 0, 0);
        quickMenuContainer.addView(closeBtn, closeLp);

        updateAllMenuStateViews();
    }

    private void updateAllMenuStateViews() {
        updateStatsBadge();
        if (adblockRow != null) {
            adblockRow.setChecked(adblockEnabled, adblockEnabled ? "ACTIVE (" + adsBlockedCount + " Ads)" : "DISABLED");
        }
        if (promoPopupsRow != null) {
            promoPopupsRow.setChecked(blockPromoPopups, blockPromoPopups ? "ENABLED" : "OFF");
        }
        if (sponsorMasterRow != null) {
            sponsorMasterRow.setChecked(sponsorBlockEnabled, sponsorBlockEnabled ? "ACTIVE" : "OFF");
        }
        if (skipNoticeRow != null) {
            skipNoticeRow.setChecked(showSkipNotice, showSkipNotice ? "ENABLED" : "OFF");
        }
        for (String[] catDef : SPONSOR_CATEGORIES) {
            CategoryModeRowView catRow = categoryRows.get(catDef[0]);
            if (catRow != null) {
                catRow.setMode(getCategoryMode(catDef[0], catDef[3]));
            }
        }
        updateSpeedButtonsStyling();
        updateAspectButtonsStyling();
        updateQualityButtonsStyling();
    }

    private void updateStatsBadge() {
        if (statsBadgeCard == null) return;
        statsBadgeCard.setText("🛡 " + adsBlockedCount + " Ads Blocked   •   ⏭ "
                + segmentsSkippedCount + " Segments Skipped (" + (secondsSavedCount / 60) + "m saved)");
    }

    private void addSectionHeader(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(Color.parseColor("#00E5FF"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.create("sans-serif-bold", Typeface.BOLD));
        tv.setPadding(0, dp(16), 0, dp(6));
        quickMenuContainer.addView(tv);
    }

    // ---------------------------------------------------------------------------
    // STATEFUL TOGGLE ROW VIEW (In-place updates with zero view re-creation!)
    // ---------------------------------------------------------------------------
    public class ToggleRowView extends LinearLayout {
        private final TextView lbl;
        private final TextView badge;
        private boolean isChecked;
        private final RowClickListener listener;

        public interface RowClickListener {
            void onRowClick(ToggleRowView row);
        }

        public ToggleRowView(Context ctx, String title, boolean checked, RowClickListener l) {
            super(ctx);
            this.isChecked = checked;
            this.listener = l;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setFocusable(true);
            setClickable(true);
            setPadding(dp(14), dp(10), dp(14), dp(10));

            GradientDrawable normalBg = new GradientDrawable();
            normalBg.setColor(Color.parseColor("#121724"));
            normalBg.setCornerRadius(dp(8));

            GradientDrawable focusBg = new GradientDrawable();
            focusBg.setColor(Color.parseColor("#223354"));
            focusBg.setCornerRadius(dp(8));
            focusBg.setStroke(dp(2), Color.parseColor("#00E5FF"));

            setBackground(normalBg);
            setOnFocusChangeListener((v, hasFocus) -> setBackground(hasFocus ? focusBg : normalBg));
            setOnClickListener(v -> {
                if (listener != null) listener.onRowClick(this);
            });

            lbl = new TextView(ctx);
            lbl.setText(title);
            lbl.setTextColor(Color.WHITE);
            lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            addView(lbl, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            badge = new TextView(ctx);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            badge.setTypeface(Typeface.create("sans-serif-bold", Typeface.BOLD));
            addView(badge);

            setChecked(isChecked, isChecked ? "ENABLED" : "OFF");

            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(2), 0, dp(2));
            setLayoutParams(lp);
        }

        public void setChecked(boolean checked, String badgeText) {
            this.isChecked = checked;
            badge.setText(badgeText);
            badge.setTextColor(checked ? Color.parseColor("#00E676") : Color.parseColor("#FF5252"));
        }
    }

    // ---------------------------------------------------------------------------
    // STATEFUL CATEGORY MODE ROW VIEW
    // ---------------------------------------------------------------------------
    public class CategoryModeRowView extends LinearLayout {
        private final TextView dot;
        private final TextView lbl;
        private final TextView badge;
        private String currentMode;
        private final String hexAccent;
        private final CatClickListener listener;

        public interface CatClickListener {
            void onCatClick(CategoryModeRowView row);
        }

        public CategoryModeRowView(Context ctx, String title, String mode, String hexCol, CatClickListener l) {
            super(ctx);
            this.currentMode = mode;
            this.hexAccent = hexCol;
            this.listener = l;

            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setFocusable(true);
            setClickable(true);
            setPadding(dp(12), dp(8), dp(12), dp(8));

            GradientDrawable normalBg = new GradientDrawable();
            normalBg.setColor(Color.parseColor("#111622"));
            normalBg.setCornerRadius(dp(6));

            GradientDrawable focusBg = new GradientDrawable();
            focusBg.setColor(Color.parseColor("#223354"));
            focusBg.setCornerRadius(dp(6));
            focusBg.setStroke(dp(2), Color.parseColor(hexCol));

            setBackground(normalBg);
            setOnFocusChangeListener((v, hasFocus) -> setBackground(hasFocus ? focusBg : normalBg));
            setOnClickListener(v -> {
                if (listener != null) listener.onCatClick(this);
            });

            dot = new TextView(ctx);
            dot.setText("● ");
            try {
                dot.setTextColor(Color.parseColor(hexCol));
            } catch (Exception e) {
                dot.setTextColor(Color.WHITE);
            }
            dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            addView(dot);

            lbl = new TextView(ctx);
            lbl.setText(title);
            lbl.setTextColor(Color.WHITE);
            lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            addView(lbl, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            badge = new TextView(ctx);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            badge.setTypeface(Typeface.create("sans-serif-bold", Typeface.BOLD));
            addView(badge);

            setMode(currentMode);

            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(2), 0, dp(2));
            setLayoutParams(lp);
        }

        public String getCurrentMode() {
            return currentMode;
        }

        public void setMode(String mode) {
            this.currentMode = mode;
            badge.setText(formatCategoryModeBadge(mode));
            if ("auto".equals(mode)) {
                badge.setTextColor(Color.parseColor("#00E676"));
            } else if ("manual".equals(mode)) {
                badge.setTextColor(Color.parseColor("#FFD600"));
            } else {
                badge.setTextColor(Color.parseColor("#78909C"));
            }
        }
    }

    private Button createSpeedButton(double spd) {
        Button btn = new Button(this);
        btn.setText(formatSpeed(spd));
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btn.setPadding(0, 0, 0, 0);
        btn.setTag(spd);

        btn.setOnClickListener(v -> {
            playbackSpeed = spd;
            prefs.edit().putFloat("playback_speed", (float) playbackSpeed).apply();
            pushConfigToJs();
            updateSpeedButtonsStyling();
            showTvToast("Speed: " + formatSpeed(spd), "#448AFF");
        });
        return btn;
    }

    private void updateSpeedButtonsStyling() {
        for (Button btn : speedButtons) {
            double spd = (double) btn.getTag();
            boolean isSelected = Math.abs(spd - playbackSpeed) < 0.01;
            btn.setTypeface(Typeface.create("sans-serif-medium", isSelected ? Typeface.BOLD : Typeface.NORMAL));

            GradientDrawable normalBg = new GradientDrawable();
            normalBg.setColor(isSelected ? Color.parseColor("#2979FF") : Color.parseColor("#141926"));
            normalBg.setCornerRadius(dp(6));

            GradientDrawable focusBg = new GradientDrawable();
            focusBg.setColor(isSelected ? Color.parseColor("#0050D6") : Color.parseColor("#253654"));
            focusBg.setCornerRadius(dp(6));
            focusBg.setStroke(dp(2), Color.WHITE);

            btn.setBackground(normalBg);
            btn.setTextColor(isSelected ? Color.WHITE : Color.parseColor("#B0BEC5"));
            btn.setOnFocusChangeListener((v, hasFocus) -> btn.setBackground(hasFocus ? focusBg : normalBg));
        }
    }

    private void addAspectButton(LinearLayout parent, String label, String mode) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btn.setPadding(0, 0, 0, 0);
        btn.setTag(mode);

        btn.setOnClickListener(v -> {
            videoAspectMode = mode;
            prefs.edit().putString("video_aspect_mode", videoAspectMode).apply();
            pushConfigToJs();
            updateAspectButtonsStyling();
            showTvToast("Aspect: " + label, "#00E676");
        });
        aspectButtons.add(btn);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        parent.addView(btn, lp);
    }

    private void updateAspectButtonsStyling() {
        for (Button btn : aspectButtons) {
            String m = (String) btn.getTag();
            boolean isSelected = m.equals(videoAspectMode);

            GradientDrawable normalBg = new GradientDrawable();
            normalBg.setColor(isSelected ? Color.parseColor("#00E676") : Color.parseColor("#141926"));
            normalBg.setCornerRadius(dp(6));

            GradientDrawable focusBg = new GradientDrawable();
            focusBg.setColor(isSelected ? Color.parseColor("#00C853") : Color.parseColor("#253654"));
            focusBg.setCornerRadius(dp(6));
            focusBg.setStroke(dp(2), Color.WHITE);

            btn.setBackground(normalBg);
            btn.setTextColor(isSelected ? Color.BLACK : Color.parseColor("#B0BEC5"));
            btn.setOnFocusChangeListener((v, hasFocus) -> btn.setBackground(hasFocus ? focusBg : normalBg));
        }
    }

    private void addQualityButton(LinearLayout parent, String label, String qVal) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btn.setPadding(0, 0, 0, 0);
        btn.setTag(qVal);

        btn.setOnClickListener(v -> {
            preferredQuality = qVal;
            prefs.edit().putString("preferred_quality", preferredQuality).apply();
            pushConfigToJs();
            updateQualityButtonsStyling();
            showTvToast("Preferred Quality: " + label, "#00E5FF");
        });
        qualityButtons.add(btn);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        parent.addView(btn, lp);
    }

    private void updateQualityButtonsStyling() {
        for (Button btn : qualityButtons) {
            String q = (String) btn.getTag();
            boolean isSelected = q.equals(preferredQuality);

            GradientDrawable normalBg = new GradientDrawable();
            normalBg.setColor(isSelected ? Color.parseColor("#00E5FF") : Color.parseColor("#141926"));
            normalBg.setCornerRadius(dp(6));

            GradientDrawable focusBg = new GradientDrawable();
            focusBg.setColor(isSelected ? Color.parseColor("#00B0FF") : Color.parseColor("#253654"));
            focusBg.setCornerRadius(dp(6));
            focusBg.setStroke(dp(2), Color.WHITE);

            btn.setBackground(normalBg);
            btn.setTextColor(isSelected ? Color.BLACK : Color.parseColor("#B0BEC5"));
            btn.setOnFocusChangeListener((v, hasFocus) -> btn.setBackground(hasFocus ? focusBg : normalBg));
        }
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
        focusBg.setStroke(dp(2), Color.parseColor("#00E5FF"));

        btn.setBackground(normalBg);
        btn.setOnFocusChangeListener((v, hasFocus) -> btn.setBackground(hasFocus ? focusBg : normalBg));
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    private void resetSettingsToDefault() {
        adblockEnabled = true;
        blockPromoPopups = true;
        sponsorBlockEnabled = true;
        showSkipNotice = true;
        preferredQuality = "1080";
        playbackSpeed = 1.0;
        videoAspectMode = "fit";

        for (String[] catDef : SPONSOR_CATEGORIES) {
            setCategoryMode(catDef[0], catDef[3]);
        }
        prefs.edit()
                .putBoolean("adblock_enabled", true)
                .putBoolean("block_promo_popups", true)
                .putBoolean("sponsorblock_enabled", true)
                .putBoolean("show_skip_notice", true)
                .putString("preferred_quality", "1080")
                .putFloat("playback_speed", 1.0f)
                .putString("video_aspect_mode", "fit")
                .apply();

        pushConfigToJs();
        updateAllMenuStateViews();
        showTvToast("Settings reset to defaults!", "#00E676");
    }

    // ---------------------------------------------------------------------------
    // OTA UPDATE ENGINE
    // ---------------------------------------------------------------------------
    private void checkForAppUpdates(boolean userInitiated) {
        if (userInitiated) {
            showTvToast("Checking GitHub Releases...", "#00E5FF");
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

                    String currentVer = "v1.7.1";
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
                                updateAllMenuStateViews();
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
    // REMOTE CONTROL KEY EVENTS & FOCUS LOCK
    // ---------------------------------------------------------------------------
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        // When Quick Menu is open, TRAP FOCUS completely inside the menu!
        if (isQuickMenuOpen()) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_MENU) {
                if (action == KeyEvent.ACTION_UP) {
                    hideQuickMenu();
                }
                return true;
            }

            // Route keyevent to the menu view hierarchy
            boolean consumed = quickMenuScrim.dispatchKeyEvent(event);
            if (consumed) return true;

            // Ensure focus never escapes to WebView while menu is open
            if (quickMenuContainer != null && quickMenuContainer.findFocus() == null) {
                if (adblockRow != null) adblockRow.requestFocus();
            }
            return true; // NEVER let events leak to WebView while menu is open!
        }

        // Dedicated keys to toggle Quick Menu: MENU, INFO, SETTINGS, GUIDE, PROG_RED/BLUE
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
            mainHandler.post(MainActivity.this::updateStatsBadge);
        }

        @JavascriptInterface
        public void showToast(String msg, String color) {
            showTvToast(msg, color);
        }
    }
}
