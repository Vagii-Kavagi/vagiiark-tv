(function () {
    if (window.__vagiiArkLoaded) return;
    window.__vagiiArkLoaded = true;

    const BRIDGE = window.VagiiBridge || null;

    // Default user configuration (synced with native Android SharedPreferences)
    const config = {
        adblockEnabled: true,
        sponsorBlockEnabled: true,
        speed: 1.0,
        fastMode: true,
        categories: {
            sponsor: true,
            selfpromo: true,
            interaction: true,
            intro: true,
            outro: true,
            preview: true,
            music_offtopic: true
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
    // 1. ULTRA-FAST CSS & RENDERING ENGINE (Strips heavy blurs/shadows for 60fps)
    // ---------------------------------------------------------------------------
    function applyFastModeCss() {
        let styleEl = document.getElementById('vagiiark-fast-css');
        if (!config.fastMode) {
            if (styleEl) styleEl.remove();
            return;
        }
        if (styleEl) return;
        styleEl = document.createElement('style');
        styleEl.id = 'vagiiark-fast-css';
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
            /* Strip GPU-heavy blurs, ambient canvas glows & complex box shadows for 1.4GB RAM MStar TVs */
            *, *::before, *::after {
                backdrop-filter: none !important;
                -webkit-backdrop-filter: none !important;
                box-shadow: none !important;
                text-shadow: none !important;
            }
            #cinematics, .ytp-cinematics, canvas.ambient-canvas {
                display: none !important;
            }
            /* Snappier 90ms focus & row scroll transitions */
            ytlr-tile-renderer, ytlr-button-renderer {
                transition-duration: 85ms !important;
            }
        `;
        (document.head || document.documentElement).appendChild(styleEl);
    }

    if (document.documentElement) {
        applyFastModeCss();
    } else {
        document.addEventListener('DOMContentLoaded', applyFastModeCss);
    }

    // ---------------------------------------------------------------------------
    // 2. ON-SCREEN TOAST & SPONSORBLOCK PROGRESS BAR MARKERS
    // ---------------------------------------------------------------------------
    function showToast(title, subtitle, accentColor) {
        try {
            if (BRIDGE && BRIDGE.showNativeToast) {
                BRIDGE.showNativeToast(title + (subtitle ? " • " + subtitle : ""), accentColor || "#00E676");
                return;
            }
        } catch (e) {}

        let el = document.getElementById('vagiiark-toast');
        if (!el) {
            el = document.createElement('div');
            el.id = 'vagiiark-toast';
            el.style.cssText = [
                'position:fixed',
                'bottom:48px',
                'right:48px',
                'z-index:2147483647',
                'background:rgba(16,18,24,0.94)',
                'color:#FFFFFF',
                'padding:14px 22px',
                'border-radius:14px',
                'border-left:5px solid #00E676',
                'font-family:Roboto,sans-serif',
                'font-size:20px',
                'font-weight:600',
                'pointer-events:none',
                'transition:opacity 0.2s ease, transform 0.2s ease',
                'opacity:0',
                'transform:translateY(10px)'
            ].join(';');
            (document.body || document.documentElement).appendChild(el);
        }
        el.style.borderLeftColor = accentColor || '#00E676';
        el.innerHTML = `<div>${title}</div>` + (subtitle ? `<div style="font-size:15px;color:#B0BEC5;margin-top:3px;font-weight:400">${subtitle}</div>` : '');
        el.style.opacity = '1';
        el.style.transform = 'translateY(0)';
        clearTimeout(el.__hideTimer);
        el.__hideTimer = setTimeout(() => {
            el.style.opacity = '0';
            el.style.transform = 'translateY(10px)';
        }, 2600);
    }

    // ---------------------------------------------------------------------------
    // 3. INNERTUBE JSON AD-STRIPPER (Blocks ads before player parses payload)
    // ---------------------------------------------------------------------------
    const AD_KEYS = [
        'adPlacements',
        'playerAds',
        'adSlots',
        'adBreakHeartbeatParams',
        'adSafetyReason',
        'masthead'
    ];

    function stripAdsFromObject(obj, depth) {
        if (!config.adblockEnabled || !obj || typeof obj !== 'object' || depth > 7) return obj;
        let stripped = 0;

        for (let i = 0; i < AD_KEYS.length; i++) {
            const key = AD_KEYS[i];
            if (key in obj && obj[key] != null) {
                delete obj[key];
                stripped++;
            }
        }

        // Clean Home / Search feed ad renderers
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
                    // Only recurse into key InnerTube containers to keep CPU overhead near zero
                    if (
                        k === 'playerResponse' ||
                        k === 'contents' ||
                        k === 'twoColumnWatchNextResults' ||
                        k === 'singleColumnBrowseResultsRenderer' ||
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
            if (parsed && typeof parsed === 'object') {
                detectVideoIdFromPlayerPayload(parsed);
                if (
                    config.adblockEnabled &&
                    typeof text === 'string' &&
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
    // 4. SPONSORBLOCK ENGINE + VIDEO SPEED CONTROLLER + DOM AD FALLBACK
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

    function getActiveCategoriesList() {
        const list = [];
        for (const k in config.categories) {
            if (config.categories[k]) list.push(k);
        }
        return list;
    }

    function onVideoDetected(videoId, title) {
        if (!videoId || videoId === currentVideoId) return;
        currentVideoId = videoId;
        currentVideoTitle = title || '';
        currentSegments = [];
        lastSkipEndTime = -1;

        try {
            if (BRIDGE && BRIDGE.onVideoChanged) {
                BRIDGE.onVideoChanged(videoId, currentVideoTitle);
            }
        } catch (e) {}

        if (!config.sponsorBlockEnabled) return;

        // Fetch SponsorBlock segments via Native Android bridge (bypasses CORS & runs off-thread)
        try {
            if (BRIDGE && BRIDGE.fetchSponsorSegments) {
                BRIDGE.fetchSponsorSegments(videoId, JSON.stringify(getActiveCategoriesList()));
                return;
            }
        } catch (e) {}

        // Browser fetch fallback
        const cats = encodeURIComponent(JSON.stringify(getActiveCategoriesList()));
        fetch(`https://sponsor.ajay.app/api/skipSegments?videoID=${encodeURIComponent(videoId)}&categories=${cats}`)
            .then(r => (r.ok ? r.json() : []))
            .then(segs => {
                if (Array.isArray(segs)) {
                    window.__vagiiArkSetSegments(videoId, segs);
                }
            })
            .catch(() => {});
    }

    window.__vagiiArkSetSegments = function (videoId, segments) {
        if (videoId !== currentVideoId) return;
        currentSegments = Array.isArray(segments) ? segments : [];
        if (currentSegments.length > 0) {
            showToast(
                `SponsorBlock Ready`,
                `${currentSegments.length} segment${currentSegments.length > 1 ? 's' : ''} loaded`,
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

    function checkDomAdAndSkip(video) {
        if (!config.adblockEnabled || !video) return false;
        const adShowing = document.querySelector('.ad-showing, .ad-interrupting, ytlr-ad-placement-renderer');
        if (adShowing) {
            const skipBtn = document.querySelector(
                '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, [class*="skip-button"]'
            );
            if (skipBtn && typeof skipBtn.click === 'function') {
                skipBtn.click();
            }
            if (isFinite(video.duration) && video.duration > 0 && video.currentTime < video.duration - 0.2) {
                video.currentTime = video.duration;
            } else {
                video.playbackRate = 16.0;
                video.muted = true;
            }
            config.stats.adsBlocked++;
            try {
                if (BRIDGE && BRIDGE.onAdBlocked) BRIDGE.onAdBlocked(1);
            } catch (e) {}
            return true;
        }
        return false;
    }

    function checkSponsorBlockSegments(video) {
        if (!config.sponsorBlockEnabled || !video || video.paused || currentSegments.length === 0) return;
        const now = video.currentTime;
        for (let i = 0; i < currentSegments.length; i++) {
            const seg = currentSegments[i];
            if (!seg || !Array.isArray(seg.segment)) continue;
            const start = Number(seg.segment[0]);
            const end = Number(seg.segment[1]);
            const cat = seg.category || 'sponsor';
            if (config.categories[cat] === false) continue;

            if (now >= start && now < end - 0.25) {
                if (Math.abs(lastSkipEndTime - end) < 0.8 && Math.abs(now - start) > 2.0) {
                    continue;
                }
                lastSkipEndTime = end;
                const saved = Math.max(1, Math.round(end - now));
                video.currentTime = end;
                config.stats.segmentsSkipped++;
                config.stats.secondsSaved += saved;

                const meta = CATEGORY_LABELS[cat] || { name: cat, color: '#00E676' };
                showToast(`Skipped ${meta.name}`, `Saved ${saved}s`, meta.color);

                try {
                    if (BRIDGE && BRIDGE.onSegmentSkipped) {
                        BRIDGE.onSegmentSkipped(cat, saved);
                    }
                } catch (e) {}
                break;
            }
        }
    }

    // Attach high-precision listeners directly to HTML5 <video> element
    let boundVideo = null;
    function attachToVideoElement() {
        const urlVid = extractVideoIdFromUrl();
        if (urlVid && urlVid !== currentVideoId) {
            onVideoDetected(urlVid, document.title || '');
        }

        const video = document.querySelector('video');
        if (!video) return;

        if (video !== boundVideo) {
            boundVideo = video;
            video.addEventListener('ratechange', () => enforceVideoSpeed(video));
            video.addEventListener('loadedmetadata', () => {
                enforceVideoSpeed(video);
                const vId = extractVideoIdFromUrl();
                if (vId) onVideoDetected(vId, document.title || '');
            });
            video.addEventListener('play', () => enforceVideoSpeed(video));
            video.addEventListener('timeupdate', () => {
                if (!checkDomAdAndSkip(video)) {
                    enforceVideoSpeed(video);
                    checkSponsorBlockSegments(video);
                }
            });
        } else {
            if (!checkDomAdAndSkip(video)) {
                enforceVideoSpeed(video);
                checkSponsorBlockSegments(video);
            }
        }
    }

    setInterval(attachToVideoElement, 250);

    // ---------------------------------------------------------------------------
    // 5. LIVE API FOR NATIVE QUICK-SETTINGS OVERLAY PANEL
    // ---------------------------------------------------------------------------
    window.__vagiiArkSetSpeed = function (speed) {
        const s = Math.max(0.25, Math.min(4.0, Number(speed) || 1.0));
        config.speed = s;
        const video = document.querySelector('video');
        if (video) video.playbackRate = s;
        showToast(`Playback Speed: ${s.toFixed(2).replace(/\.00$/, '.0').replace(/(\.\d)0$/, '$1')}x`, 'Applied immediately', '#FFD600');
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
        applyFastModeCss();
        const video = document.querySelector('video');
        if (video) enforceVideoSpeed(video);
        if (currentVideoId && config.sponsorBlockEnabled && BRIDGE && BRIDGE.fetchSponsorSegments) {
            BRIDGE.fetchSponsorSegments(currentVideoId, JSON.stringify(getActiveCategoriesList()));
        }
    };
})();
