/*
 * VagiiArk TV Live Hotfix Patch (Fetched automatically from GitHub: Vagii-Kavagi/vagiiark-tv)
 * Push updates to this file on the `main` branch to hot-patch AdBlock selectors, SponsorBlock rules,
 * or UI tweaks in-app without requiring an APK reinstall!
 */
(function () {
    window.__vagiiArkHotfixVersion = "1.1.0-live";

    let patchStyle = document.getElementById('vagiiark-hotfix-css');
    if (!patchStyle) {
        patchStyle = document.createElement('style');
        patchStyle.id = 'vagiiark-hotfix-css';
        (document.head || document.documentElement).appendChild(patchStyle);
    }
    patchStyle.textContent = `
        yt-formatted-string,
        yt-formatted-string *,
        ytlr-video-title-tray,
        ytlr-video-title-tray *,
        ytlr-player-header-renderer,
        ytlr-player-header-renderer *,
        ytlr-progress-bar span,
        ytlr-multi-markers-player-bar-renderer span {
            background-color: transparent !important;
        }
    `;
})();
