(function () {
    if (window.__vagiiArkLoaded) return;
    window.__vagiiArkLoaded = true;

    const BRIDGE = window.VagiiBridge || null;

    // ---------------------------------------------------------------------------
    // 0. FORCE HARDWARE H.264 / AVC1 + 1080p60 CAP ON MSTAR m5621 SO VIDEO & LIVE
    //    STREAMS NEVER TRIGGER VP9/AV1 CPU FALLBACK OR 67% CPU THERMAL THROTTLING!
    // ---------------------------------------------------------------------------
    try {
        if (window.MediaSource && typeof MediaSource.isTypeSupported === 'function') {
            const origIsTypeSupported = MediaSource.isTypeSupported.bind(MediaSource);
            MediaSource.isTypeSupported = function (mimeType) {
                if (!mimeType || typeof mimeType !== 'string') return false;
                const lower = mimeType.toLowerCase();
                // Block AV1 and VP9/WebM on MStar WebView so YouTube serves H.264 (avc1)
                // which decodes at 0% CPU on OMX.MS.AVC.Decoder (111 fps hardware VPU)
                if (lower.indexOf('av01') !== -1 || lower.indexOf('vp9') !== -1 || lower.indexOf('vp09') !== -1 || lower.indexOf('webm') !== -1) {
                    return false;
                }
                // Cap resolution at 1080p (height <= 1080, width <= 1920)
                const hMatch = lower.match(/height=(\d+)/);
                if (hMatch && parseInt(hMatch[1], 10) > 1088) return false;
                const wMatch = lower.match(/width=(\d+)/);
                if (wMatch && parseInt(wMatch[1], 10) > 1920) return false;
                return origIsTypeSupported(mimeType);
            };
        }
        // Lock devicePixelRatio to 1.0 so YouTube doesn't rasterize 4K textures on a 1080p panel
        if (window.devicePixelRatio && window.devicePixelRatio > 1.25) {
            Object.defineProperty(window, 'devicePixelRatio', { get: function () { return 1.0; } });
        }
    } catch (e) {}

    // Modes for SponsorBlock categories: 'auto' (Auto-Skip), 'manual' (Prompt to Skip with OK), 'off' (Do nothing)
    const config = {
        adblockEnabled: true,
        sponsorBlockEnabled: true,
        speed: 1.0,
        videoAspectMode: 'fit', // 'fit' (Original), 'fill' (Zoom to Fill 16:9 Screen), 'stretch' (Stretch 16:9)
        fastMode: true,
        categories: {
            sponsor: 'auto',
            selfpromo: 'auto',
            interaction: 'auto',
            intro: 'manual',
            outro: 'manual',
            preview: 'manual',
            music_offtopic: 'auto'
        },
        stats: {
            adsBlocked: 0,
            segmentsSkipped: 0,
            secondsSaved: 0
        }
    };

    try {
        if (BRIDGE && BRIDGE.getConfigJson) {
            const saved = JSON.parse(BRIDGE.getConfigJson());
            Object.assign(config, saved);
            if (saved.categories) Object.assign(config.categories, saved.categories);
            if (saved.stats) Object.assign(config.stats, saved.stats);
        }
    } catch (e) {}

    window.__vagiiArkConfig = config;

    // ---------------------------------------------------------------------------
    // 1. CLEAN YouTube TV STYLING & AD-CONTAINER HIDING
    //    (Zero GPU blur filters — uses crisp translucent RGBA so Mali-470 runs at 60fps!)
    // ---------------------------------------------------------------------------
    function applyCustomCss() {
        let styleEl = document.getElementById('vagiiark-fast-css');
        if (!styleEl) {
            styleEl = document.createElement('style');
            styleEl.id = 'vagiiark-fast-css';
            (document.head || document.documentElement).appendChild(styleEl);
        }

        styleEl.textContent = `
            /* Hide remaining DOM-level ad containers and promo banners immediately */
            ytlr-ad-placement-renderer,
            ytlr-masthead-ad-renderer,
            ytlr-promoted-video-renderer,
            ytlr-sparkles-web-renderer,
            ytlr-companion-ad-renderer,
            .ytp-ad-module,
            .ytp-ad-overlay-container,
            .ytp-ad-player-overlay,
            .video-ads {
                display: none !important;
                visibility: hidden !important;
                width: 0 !important;
                height: 0 !important;
            }
            /* Hide any WebView default video poster / play-icon flash */
            video::-webkit-media-controls,
            video::-webkit-media-controls-overlay-play-button,
            video::-webkit-media-controls-start-playback-button,
            .ytp-cued-thumbnail-overlay,
            .ytp-spinner,
            .html5-video-loader {
                display: none !important;
                opacity: 0 !important;
                pointer-events: none !important;
            }
            /* Ensure video title, channel metadata, chapter titles & time labels NEVER get solid black label backgrounds */
            yt-formatted-string,
            yt-formatted-string *,
            ytlr-video-title-tray,
            ytlr-video-title-tray *,
            .ytlr-video-title-tray,
            ytlr-player-header-renderer,
            ytlr-player-header-renderer *,
            ytlr-progress-bar,
            ytlr-progress-bar *,
            ytlr-multi-markers-player-bar-renderer,
            ytlr-multi-markers-player-bar-renderer *,
            ytlr-watch-metadata,
            ytlr-watch-metadata *,
            .ytlr-video-owner-renderer,
            .ytlr-video-owner-renderer *,
            span[style*="background"],
            div[style*="background-color: rgb(0, 0, 0)"],
            span[style*="background-color: rgb(0, 0, 0)"] {
                background-color: transparent !important;
            }
            /* Restore YouTube TV's soft top scrim gradient so white text is crisp without black boxes */
            ytlr-player-header-renderer {
                background: linear-gradient(180deg, rgba(0,0,0,0.68) 0%, rgba(0,0,0,0) 100%) !important;
            }
            /* Disable heavy ambient canvas and any backdrop-filter blur that chokes Mali-470 GPU */
            #cinematics, .ytp-cinematics, canvas.ambient-canvas {
                display: none !important;
            }
            *, *::before, *::after {
                backdrop-filter: none !important;
                -webkit-backdrop-filter: none !important;
            }
            ${config.videoAspectMode === 'fill' ? `
            /* Fullscreen Zoom-to-Fill: expands 4:3 / 1.5:1 music videos to fill the 16:9 TV screen with zero black sidebars */
            .html5-main-video, video {
                width: 100vw !important;
                height: 100vh !important;
                left: 0 !important;
                top: 0 !important;
                object-fit: cover !important;
            }
            ` : config.videoAspectMode === 'stretch' ? `
            /* Fullscreen 16:9 Stretch: stretches video edge-to-edge without cropping */
            .html5-main-video, video {
                width: 100vw !important;
                height: 100vh !important;
                left: 0 !important;
                top: 0 !important;
                object-fit: fill !important;
            }
            ` : ''}
            ${config.fastMode ? `
            /* Snappy 70ms D-pad tile focus transitions without laggy multi-layer repaints */
            ytlr-tile-renderer, ytlr-button-renderer {
                transition-duration: 70ms !important;
            }
            /* Disable animated video previews while scrolling the home grid so D-pad scrolling stays 60fps */
            ytlr-tile-renderer ytlr-thumbnail-details + div video {
                display: none !important;
            }
            ` : ''}
        `;
    }

    if (document.documentElement) {
        applyCustomCss();
    } else {
        document.addEventListener('DOMContentLoaded', applyCustomCss);
    }

    function formatSpeedLabel(s) {
        const n = Number(s) || 1.0;
        return n.toFixed(2).replace(/\.00$/, '.0').replace(/(\.\d)0$/, '$1') + 'x';
    }

    // ---------------------------------------------------------------------------
    // 2. MANUAL SPONSORBLOCK SKIP PROMPT BANNER (When category is set to 'manual')
    // ---------------------------------------------------------------------------
    let activeManualSegment = null;
    let dismissedSegmentUuid = null;

    function showManualSkipPrompt(seg, meta) {
        activeManualSegment = seg;
        const end = Math.round(Number(seg.segment[1]) - Number(seg.segment[0]));
        try {
            if (BRIDGE && BRIDGE.onManualSkipPromptState) {
                BRIDGE.onManualSkipPromptState(true, seg.segment[1], meta.name, end);
            }
        } catch (e) {}

        let el = document.getElementById('vagiiark-manual-skip');
        if (!el && document.body) {
            el = document.createElement('div');
            el.id = 'vagiiark-manual-skip';
            el.style.cssText = [
                'position:fixed',
                'bottom:125px',
                'right:48px',
                'z-index:2147483646',
                'background:rgba(18,20,28,0.95)',
                'color:#FFFFFF',
                'padding:14px 22px',
                'border-radius:28px',
                'border:2px solid #00E676',
                'font-family:Roboto,sans-serif',
                'font-size:18px',
                'font-weight:600',
                'display:none',
                'align-items:center',
                'gap:12px'
            ].join(';');
            document.body.appendChild(el);
        }
        if (el) {
            el.style.borderColor = meta.color || '#00E676';
            el.innerHTML = `<span style="background:${meta.color || '#00E676'};color:#000;padding:3px 10px;border-radius:12px;font-size:14px;font-weight:700">PRESS OK</span>`
                + `<span>Skip ${meta.name} (${end}s)</span>`;
            el.style.display = 'flex';
        }
    }

    function hideManualSkipPrompt() {
        if (!activeManualSegment) return;
        activeManualSegment = null;
        try {
            if (BRIDGE && BRIDGE.onManualSkipPromptState) {
                BRIDGE.onManualSkipPromptState(false, 0, '', 0);
            }
        } catch (e) {}
        const el = document.getElementById('vagiiark-manual-skip');
        if (el) el.style.display = 'none';
    }

    window.__vagiiArkTriggerManualSkip = function () {
        if (!activeManualSegment) return false;
        const video = document.querySelector('video');
        if (!video) return false;
        const end = Number(activeManualSegment.segment[1]);
        const saved = Math.max(1, Math.round(end - video.currentTime));
        const cat = activeManualSegment.category || 'sponsor';
        const meta = CATEGORY_LABELS[cat] || { name: cat, color: '#00E676' };
        dismissedSegmentUuid = activeManualSegment.UUID || (activeManualSegment.segment[0] + '-' + activeManualSegment.segment[1]);
        hideManualSkipPrompt();

        video.currentTime = end;
        config.stats.segmentsSkipped++;
        config.stats.secondsSaved += saved;
        if (BRIDGE && BRIDGE.showNativeToast) {
            BRIDGE.showNativeToast(`Skipped ${meta.name} • Saved ${saved}s`, meta.color);
        }
        if (BRIDGE && BRIDGE.onSegmentSkipped) {
            BRIDGE.onSegmentSkipped(cat, saved);
        }
        return true;
    };

    // ---------------------------------------------------------------------------
    // 3. ZERO-OVERHEAD INNERTUBE JSON AD-STRIPPER
    // ---------------------------------------------------------------------------
    const AD_KEYS = [
        'adPlacements',
        'playerAds',
        'adSlots',
        'masthead'
    ];

    function stripAdsFromObject(obj, depth) {
        if (!config.adblockEnabled || !obj || typeof obj !== 'object' || depth > 5) return obj;
        let stripped = 0;

        for (let i = 0; i < AD_KEYS.length; i++) {
            const key = AD_KEYS[i];
            if (key in obj && obj[key] != null) {
                if (Array.isArray(obj[key])) {
                    if (obj[key].length > 0) {
                        obj[key] = [];
                        stripped++;
                    }
                } else {
                    delete obj[key];
                    stripped++;
                }
            }
        }

        if (Array.isArray(obj.contents)) {
            const before = obj.contents.length;
            obj.contents = obj.contents.filter(item => {
                if (!item || typeof item !== 'object') return true;
                return !(
                    item.adSlotRenderer ||
                    item.promotedVideoRenderer ||
                    item.promotedSparklesWebRenderer ||
                    item.mastheadAdRenderer ||
                    (item.richItemRenderer && item.richItemRenderer.content && item.richItemRenderer.content.adSlotRenderer)
                );
            });
            if (obj.contents.length < before) {
                stripped += (before - obj.contents.length);
            }
        }

        if (stripped > 0) {
            config.stats.adsBlocked += stripped;
            try {
                if (BRIDGE && BRIDGE.onAdBlocked) BRIDGE.onAdBlocked(stripped);
            } catch (e) {}
        }

        for (const k in obj) {
            if (Object.prototype.hasOwnProperty.call(obj, k)) {
                const val = obj[k];
                if (val && typeof val === 'object') {
                    if (
                        k === 'playerResponse' ||
                        k === 'contents' ||
                        k === 'tvBrowseRenderer' ||
                        k === 'sectionListRenderer' ||
                        k === 'shelfRenderer' ||
                        k === 'horizontalListRenderer' ||
                        k === 'gridRenderer' ||
                        depth < 2
                    ) {
                        stripAdsFromObject(val, depth + 1);
                    }
                }
            }
        }
        return obj;
    }

    function detectVideoIdFromPlayerPayload(obj) {
        if (!obj || typeof obj !== 'object') return;
        const details = obj.videoDetails || (obj.playerResponse && obj.playerResponse.videoDetails);
        if (details && details.videoId) {
            onVideoDetected(details.videoId, details.title || '');
        }
    }

    const origJsonParse = JSON.parse;
    JSON.parse = function (text, reviver) {
        const parsed = origJsonParse.call(this, text, reviver);
        try {
            if (parsed && typeof parsed === 'object' && typeof text === 'string' && text.length > 120) {
                if (text.indexOf('"videoDetails"') !== -1) {
                    detectVideoIdFromPlayerPayload(parsed);
                }
                if (
                    config.adblockEnabled &&
                    (text.indexOf('adPlacements') !== -1 ||
                     text.indexOf('playerAds') !== -1 ||
                     text.indexOf('adSlots') !== -1 ||
                     text.indexOf('masthead') !== -1 ||
                     text.indexOf('adSlotRenderer') !== -1)
                ) {
                    stripAdsFromObject(parsed, 0);
                }
            }
        } catch (e) {}
        return parsed;
    };

    if (window.Response && Response.prototype && Response.prototype.json) {
        const origResJson = Response.prototype.json;
        Response.prototype.json = function () {
            return origResJson.call(this).then(data => {
                try {
                    detectVideoIdFromPlayerPayload(data);
                    stripAdsFromObject(data, 0);
                } catch (e) {}
                return data;
            });
        };
    }

    // ---------------------------------------------------------------------------
    // 4. EVENT-DRIVEN SPONSORBLOCK & SPEED ENGINE (Zero 250ms DOM Polling!)
    // ---------------------------------------------------------------------------
    let currentVideoId = null;
    let currentVideoTitle = '';
    let currentSegments = [];
    let lastSkipEndTime = -1;

    const CATEGORY_LABELS = {
        sponsor: { name: 'Sponsor', color: '#00D400' },
        selfpromo: { name: 'Unpaid / Self Promo', color: '#FFFF00' },
        interaction: { name: 'Interaction Reminder', color: '#CC00FF' },
        intro: { name: 'Intermission / Intro', color: '#00FFFF' },
        outro: { name: 'Endcards / Credits', color: '#0202ED' },
        preview: { name: 'Preview / Recap', color: '#008FD6' },
        music_offtopic: { name: 'Non-Music Section', color: '#FF9900' }
    };

    function getCategoryMode(cat) {
        const val = config.categories[cat];
        if (val === true || val === 'auto') return 'auto';
        if (val === 'manual') return 'manual';
        return 'off';
    }

    function getActiveCategoriesList() {
        const list = [];
        for (const k in CATEGORY_LABELS) {
            if (getCategoryMode(k) !== 'off') list.push(k);
        }
        return list;
    }

    function onVideoDetected(videoId, title) {
        if (!videoId || videoId === currentVideoId) return;
        currentVideoId = videoId;
        currentVideoTitle = title || '';
        currentSegments = [];
        lastSkipEndTime = -1;
        dismissedSegmentUuid = null;
        hideManualSkipPrompt();

        try {
            if (BRIDGE && BRIDGE.onVideoChanged) {
                BRIDGE.onVideoChanged(videoId, currentVideoTitle);
            }
        } catch (e) {}

        if (!config.sponsorBlockEnabled) return;
        const activeCats = getActiveCategoriesList();
        if (activeCats.length === 0) return;

        try {
            if (BRIDGE && BRIDGE.fetchSponsorSegments) {
                BRIDGE.fetchSponsorSegments(videoId, JSON.stringify(activeCats));
            }
        } catch (e) {}
    }

    window.__vagiiArkSetSegments = function (videoId, segments) {
        if (videoId !== currentVideoId) return;
        currentSegments = Array.isArray(segments) ? segments : [];
        if (currentSegments.length > 0 && BRIDGE && BRIDGE.showNativeToast) {
            BRIDGE.showNativeToast(
                `SponsorBlock Ready • ${currentSegments.length} segment${currentSegments.length > 1 ? 's' : ''}`,
                '#00B0FF'
            );
        }
    };

    function extractVideoIdFromUrl() {
        try {
            const href = location.href || '';
            const hash = location.hash || '';
            let m = href.match(/[?&]v=([a-zA-Z0-9_-]{11})/) || hash.match(/[?&]v=([a-zA-Z0-9_-]{11})/);
            if (m && m[1]) return m[1];
        } catch (e) {}
        return null;
    }

    function enforceVideoSpeed(video) {
        if (!video) return;
        const target = Number(config.speed) || 1.0;
        if (Math.abs(video.playbackRate - target) > 0.01) {
            video.playbackRate = target;
        }
    }

    function checkSponsorBlockSegments(video) {
        if (!config.sponsorBlockEnabled || !video || video.paused || currentSegments.length === 0) {
            hideManualSkipPrompt();
            return;
        }
        const now = video.currentTime;
        let inManualSegment = false;

        for (let i = 0; i < currentSegments.length; i++) {
            const seg = currentSegments[i];
            if (!seg || !Array.isArray(seg.segment)) continue;
            const start = Number(seg.segment[0]);
            const end = Number(seg.segment[1]);
            const cat = seg.category || 'sponsor';
            const mode = getCategoryMode(cat);
            if (mode === 'off') continue;

            if (now >= start && now < end - 0.25) {
                const meta = CATEGORY_LABELS[cat] || { name: cat, color: '#00E676' };
                const segId = seg.UUID || (start + '-' + end);

                if (mode === 'auto') {
                    if (Math.abs(lastSkipEndTime - end) < 0.8 && Math.abs(now - start) > 2.0) {
                        continue;
                    }
                    lastSkipEndTime = end;
                    const saved = Math.max(1, Math.round(end - now));
                    video.currentTime = end;
                    config.stats.segmentsSkipped++;
                    config.stats.secondsSaved += saved;

                    if (BRIDGE && BRIDGE.showNativeToast) {
                        BRIDGE.showNativeToast(`Auto-Skipped ${meta.name} • Saved ${saved}s`, meta.color);
                    }
                    if (BRIDGE && BRIDGE.onSegmentSkipped) {
                        BRIDGE.onSegmentSkipped(cat, saved);
                    }
                    hideManualSkipPrompt();
                    return;
                } else if (mode === 'manual') {
                    if (dismissedSegmentUuid !== segId) {
                        inManualSegment = true;
                        showManualSkipPrompt(seg, meta);
                    }
                    break;
                }
            }
        }

        if (!inManualSegment && activeManualSegment) {
            hideManualSkipPrompt();
        }
    }

    // Use lightweight event delegation on <video> events instead of 250ms setInterval polling!
    document.addEventListener('loadedmetadata', function (e) {
        const video = e.target;
        if (video && video.tagName === 'VIDEO') {
            video.removeAttribute('poster');
            enforceVideoSpeed(video);
            const vId = extractVideoIdFromUrl();
            if (vId) onVideoDetected(vId, document.title || '');
        }
    }, true);

    document.addEventListener('play', function (e) {
        const video = e.target;
        if (video && video.tagName === 'VIDEO') {
            video.removeAttribute('poster');
            enforceVideoSpeed(video);
        }
    }, true);

    document.addEventListener('ratechange', function (e) {
        const video = e.target;
        if (video && video.tagName === 'VIDEO') {
            enforceVideoSpeed(video);
        }
    }, true);

    let lastTimeCheckMs = 0;
    document.addEventListener('timeupdate', function (e) {
        const nowMs = Date.now();
        if (nowMs - lastTimeCheckMs < 350) return;
        lastTimeCheckMs = nowMs;
        const video = e.target;
        if (video && video.tagName === 'VIDEO') {
            checkSponsorBlockSegments(video);
        }
    }, true);

    window.addEventListener('hashchange', function () {
        const vId = extractVideoIdFromUrl();
        if (vId) onVideoDetected(vId, document.title || '');
    });

    // ---------------------------------------------------------------------------
    // 5. LIVE API FOR NATIVE QUICK-SETTINGS OVERLAY PANEL
    // ---------------------------------------------------------------------------
    window.__vagiiArkSetSpeed = function (speed) {
        const s = Math.max(0.25, Math.min(4.0, Number(speed) || 1.0));
        config.speed = s;
        const video = document.querySelector('video');
        if (video) video.playbackRate = s;
        if (BRIDGE && BRIDGE.showNativeToast) {
            BRIDGE.showNativeToast(`Playback Speed: ${formatSpeedLabel(s)}`, '#FFD600');
        }
    };

    window.__vagiiArkStepSpeed = function (delta) {
        const next = Math.round(((Number(config.speed) || 1.0) + delta) * 4) / 4;
        const clamped = Math.max(0.25, Math.min(4.0, next));
        window.__vagiiArkSetSpeed(clamped);
        try {
            if (BRIDGE && BRIDGE.onSpeedChangedFromJs) BRIDGE.onSpeedChangedFromJs(clamped);
        } catch (e) {}
    };

    window.__vagiiArkUpdateConfig = function (newConfig) {
        if (!newConfig || typeof newConfig !== 'object') return;
        Object.assign(config, newConfig);
        if (newConfig.categories) Object.assign(config.categories, newConfig.categories);
        applyCustomCss();
        const video = document.querySelector('video');
        if (video) enforceVideoSpeed(video);
        if (currentVideoId && config.sponsorBlockEnabled && BRIDGE && BRIDGE.fetchSponsorSegments) {
            BRIDGE.fetchSponsorSegments(currentVideoId, JSON.stringify(getActiveCategoriesList()));
        }
    };
})();
