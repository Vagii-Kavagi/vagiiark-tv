(function () {
    if (window.__vagiiArkLoaded) return;
    window.__vagiiArkLoaded = true;

    const BRIDGE = window.VagiiBridge || null;

    // ---------------------------------------------------------------------------
    // 0. MEDIA CAPABILITIES HOOKS - UNLOCK 1080p60 & 1080p PREMIUM
    // ---------------------------------------------------------------------------
    try {
        if (navigator.mediaCapabilities && typeof navigator.mediaCapabilities.decodingInfo === 'function') {
            const origDecodingInfo = navigator.mediaCapabilities.decodingInfo.bind(navigator.mediaCapabilities);
            navigator.mediaCapabilities.decodingInfo = async function (mediaConfig) {
                const res = await origDecodingInfo(mediaConfig);
                if (mediaConfig && mediaConfig.video) {
                    const h = mediaConfig.video.height || 0;
                    const w = mediaConfig.video.width || 0;
                    if (h <= 1088 && w <= 1920) {
                        return {
                            supported: true,
                            smooth: true,
                            powerEfficient: true,
                            keySystemAccess: res ? res.keySystemAccess : null
                        };
                    }
                }
                return res;
            };
        }

        if (window.MediaSource && typeof MediaSource.isTypeSupported === 'function') {
            const origIsTypeSupported = MediaSource.isTypeSupported.bind(MediaSource);
            MediaSource.isTypeSupported = function (mimeType) {
                if (!mimeType || typeof mimeType !== 'string') return false;
                const lower = mimeType.toLowerCase();
                if (lower.indexOf('av01') !== -1) return false;
                const hMatch = lower.match(/height=(\d+)/);
                if (hMatch && parseInt(hMatch[1], 10) > 1088) return false;
                const wMatch = lower.match(/width=(\d+)/);
                if (wMatch && parseInt(wMatch[1], 10) > 1920) return false;

                const cleanMime = mimeType.split(';').filter(function (part) {
                    const p = part.trim().toLowerCase();
                    return !(p.startsWith('width=') || p.startsWith('height=') || p.startsWith('framerate='));
                }).join('; ').replace(/avc1\.64002a/gi, 'avc1.640028');
                return origIsTypeSupported(cleanMime) || origIsTypeSupported(mimeType);
            };
        }

        // Set default preferred quality in localStorage
        const nowTs = Date.now();
        localStorage.setItem('yt-player-quality', JSON.stringify({
            data: JSON.stringify({ quality: 1080, previousQuality: 1080 }),
            expiration: nowTs + 31536000000,
            creation: nowTs
        }));
    } catch (e) {}

    // ---------------------------------------------------------------------------
    // 1. CONFIGURATION
    // ---------------------------------------------------------------------------
    const config = {
        adblockEnabled: true,
        blockPromoPopups: true,
        sponsorBlockEnabled: true,
        showSkipNotice: true,
        preferredQuality: '1080',
        speed: 1.0,
        videoAspectMode: 'fit',
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
    // 2. STYLING FIXES (Eliminates ugly black highlight rectangles on titles)
    // ---------------------------------------------------------------------------
    function applyCustomCss() {
        let styleEl = document.getElementById('vagiiark-css');
        if (!styleEl) {
            styleEl = document.createElement('style');
            styleEl.id = 'vagiiark-css';
            (document.head || document.documentElement).appendChild(styleEl);
        }

        styleEl.textContent = `
            /* Hide DOM-level ad containers & promo banners */
            ytlr-ad-placement-renderer,
            ytlr-masthead-ad-renderer,
            ytlr-promoted-video-renderer,
            ytlr-sparkles-web-renderer,
            ytlr-companion-ad-renderer,
            ytlr-ad-feed-entry-renderer,
            ytlr-tile-renderer:has([aria-label*="Sponsored"]),
            .ytp-ad-module,
            .ytp-ad-overlay-container,
            .ytp-ad-player-overlay,
            .video-ads {
                display: none !important;
                visibility: hidden !important;
                width: 0 !important;
                height: 0 !important;
            }
            ${config.blockPromoPopups ? `
            /* Suppress promo popups, survey dialogs, and upsells */
            ytlr-mealbar-promo-renderer,
            ytlr-upsell-dialog-renderer,
            .yt-mealbar-promo-renderer,
            ytlr-ypc-offer-renderer {
                display: none !important;
            }
            ` : ''}

            /* FIX: Completely remove solid black background highlight boxes on video titles & channel metadata! */
            ytlr-tile-renderer,
            ytlr-tile-renderer *,
            ytlr-tile-renderer yt-formatted-string,
            ytlr-tile-renderer yt-formatted-string *,
            ytlr-tile-renderer #title,
            ytlr-tile-renderer #title *,
            ytlr-tile-renderer #metadata,
            ytlr-tile-renderer #metadata *,
            ytlr-tile-renderer #metadata-line,
            ytlr-tile-renderer #metadata-line *,
            .ytlr-tile-renderer yt-formatted-string,
            .ytlr-tile-renderer yt-formatted-string *,
            ytlr-watch-metadata,
            ytlr-watch-metadata *,
            span.yt-core-attributed-string,
            span.yt-core-attributed-string *,
            .ytlr-video-title-tray,
            .ytlr-video-title-tray * {
                background: transparent !important;
                background-color: transparent !important;
                text-shadow: 0 1px 4px rgba(0, 0, 0, 0.85) !important;
            }

            /* Suppress WebView default play icon / poster flash */
            video::-webkit-media-controls,
            video::-webkit-media-controls-overlay-play-button,
            video::-webkit-media-controls-start-playback-button,
            .ytp-cued-thumbnail-overlay {
                display: none !important;
                opacity: 0 !important;
                pointer-events: none !important;
            }

            /* Fast D-pad navigation */
            ${config.fastMode ? `
            ytlr-tile-renderer, ytlr-button-renderer {
                transition-duration: 80ms !important;
            }
            ` : ''}

            /* Aspect ratio handling */
            ${config.videoAspectMode === 'fill' ? `
            .html5-main-video {
                object-fit: cover !important;
            }
            ` : config.videoAspectMode === 'stretch' ? `
            .html5-main-video {
                object-fit: fill !important;
            }
            ` : `
            .html5-main-video {
                object-fit: contain !important;
            }
            `}
        `;
    }

    if (document.documentElement) {
        applyCustomCss();
    } else {
        document.addEventListener('DOMContentLoaded', applyCustomCss);
    }

    // ---------------------------------------------------------------------------
    // 3. SPONSORBLOCK CLIENT & MANUAL SKIP PROMPT
    // ---------------------------------------------------------------------------
    let currentVideoId = null;
    let videoSegments = [];
    let segmentsLoading = false;
    let activeManualSegment = null;
    let dismissedSegmentUuid = null;

    function createManualSkipPrompt() {
        let el = document.getElementById('vagiiark-skip-prompt');
        if (!el) {
            el = document.createElement('div');
            el.id = 'vagiiark-skip-prompt';
            el.tabIndex = 0;
            el.style.cssText = `
                position: fixed;
                bottom: 60px;
                right: 60px;
                z-index: 999999;
                background: rgba(14, 18, 28, 0.95);
                border: 2px solid #00E676;
                border-radius: 28px;
                padding: 14px 28px;
                color: #FFFFFF;
                font-family: sans-serif;
                font-size: 18px;
                font-weight: bold;
                display: none;
                align-items: center;
                gap: 14px;
                box-shadow: 0 10px 30px rgba(0,0,0,0.85);
                cursor: pointer;
            `;
            el.addEventListener('click', skipActiveManualSegment);
            el.addEventListener('keydown', function (e) {
                if (e.key === 'Enter' || e.keyCode === 13 || e.keyCode === 23) {
                    skipActiveManualSegment();
                    e.preventDefault();
                    e.stopPropagation();
                }
            });
            document.body.appendChild(el);
        }
        return el;
    }

    function showManualSkipPrompt(seg, label, color) {
        activeManualSegment = seg;
        const prompt = createManualSkipPrompt();
        prompt.style.borderColor = color || '#00E676';
        prompt.innerHTML = `
            <span style="color:${color || '#00E676'}">⏭ Skip ${label || 'Segment'}</span>
            <span style="background:#24324D; padding:4px 12px; border-radius:14px; font-size:14px; color:#FFFFFF;">Press OK</span>
        `;
        prompt.style.display = 'flex';
        prompt.focus();
    }

    function hideManualSkipPrompt() {
        const prompt = document.getElementById('vagiiark-skip-prompt');
        if (prompt) prompt.style.display = 'none';
        activeManualSegment = null;
    }

    function skipActiveManualSegment() {
        if (!activeManualSegment) return;
        const video = document.querySelector('video');
        if (video) {
            video.currentTime = activeManualSegment.end;
            if (BRIDGE && BRIDGE.recordSponsorSkipped) {
                BRIDGE.recordSponsorSkipped(activeManualSegment.category, activeManualSegment.end - activeManualSegment.start);
            }
            if (config.showSkipNotice && BRIDGE && BRIDGE.showToast) {
                BRIDGE.showToast("Skipped " + formatCategoryName(activeManualSegment.category), "#00E676");
            }
        }
        dismissedSegmentUuid = activeManualSegment.uuid;
        hideManualSkipPrompt();
    }

    function formatCategoryName(cat) {
        const map = {
            sponsor: 'Sponsor',
            selfpromo: 'Self Promotion',
            interaction: 'Subscribe Reminder',
            intro: 'Intro',
            outro: 'Credits',
            preview: 'Preview',
            music_offtopic: 'Non-Music Part'
        };
        return map[cat] || cat;
    }

    function getCategoryColor(cat) {
        const map = {
            sponsor: '#00E676',
            selfpromo: '#FFD600',
            interaction: '#E040FB',
            intro: '#00E5FF',
            outro: '#3D5AFE',
            preview: '#00B0FF',
            music_offtopic: '#FF9100'
        };
        return map[cat] || '#00E676';
    }

    async function fetchSponsorSegments(videoId) {
        if (!videoId || !config.sponsorBlockEnabled) return;
        segmentsLoading = true;
        videoSegments = [];
        try {
            const cats = Object.keys(config.categories).filter(c => config.categories[c] !== 'off');
            if (cats.length === 0) return;
            const url = `https://sponsor.ajay.app/api/skipSegments?videoID=${videoId}&categories=${encodeURIComponent(JSON.stringify(cats))}`;
            const res = await fetch(url);
            if (res.ok) {
                const data = await res.json();
                videoSegments = data.map(item => ({
                    uuid: item.UUID,
                    start: item.segment[0],
                    end: item.segment[1],
                    category: item.category
                }));
            }
        } catch (e) {} finally {
            segmentsLoading = false;
        }
    }

    function checkSponsorBlock(video) {
        if (!config.sponsorBlockEnabled || !videoSegments.length || video.paused) {
            if (activeManualSegment) hideManualSkipPrompt();
            return;
        }
        const time = video.currentTime;
        let matched = null;
        for (const seg of videoSegments) {
            if (time >= (seg.start - 0.25) && time < (seg.end - 0.3)) {
                matched = seg;
                break;
            }
        }

        if (!matched) {
            if (activeManualSegment) hideManualSkipPrompt();
            return;
        }

        const mode = config.categories[matched.category] || 'auto';
        if (mode === 'auto') {
            video.currentTime = matched.end;
            if (BRIDGE && BRIDGE.recordSponsorSkipped) {
                BRIDGE.recordSponsorSkipped(matched.category, matched.end - matched.start);
            }
            if (config.showSkipNotice && BRIDGE && BRIDGE.showToast) {
                BRIDGE.showToast("Skipped " + formatCategoryName(matched.category), getCategoryColor(matched.category));
            }
        } else if (mode === 'manual') {
            if (dismissedSegmentUuid !== matched.uuid && (!activeManualSegment || activeManualSegment.uuid !== matched.uuid)) {
                showManualSkipPrompt(matched, formatCategoryName(matched.category), getCategoryColor(matched.category));
            }
        }
    }

    // ---------------------------------------------------------------------------
    // 4. QUALITY & SPEED ENFORCEMENT
    // ---------------------------------------------------------------------------
    let qualityAppliedForVideoId = null;

    function applyInitialQuality(vId) {
        if (!vId || qualityAppliedForVideoId === vId) return;
        qualityAppliedForVideoId = vId;

        const targetQ = config.preferredQuality === '720' ? 'hd720' : (config.preferredQuality === '1080' ? 'hd1080' : 'auto');
        if (targetQ === 'auto') return;

        try {
            const mp = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
            if (mp) {
                if (typeof mp.setPlaybackQualityRange === 'function') {
                    mp.setPlaybackQualityRange(targetQ, targetQ);
                }
                if (typeof mp.setPlaybackQuality === 'function') {
                    mp.setPlaybackQuality(targetQ);
                }
            }
        } catch (e) {}
    }

    function enforceSpeed(video) {
        if (!video || !config.speed) return;
        if (Math.abs(video.playbackRate - config.speed) > 0.01) {
            video.playbackRate = config.speed;
        }
    }

    function extractVideoId() {
        try {
            const href = window.location.href;
            const match = href.match(/[?&]v=([a-zA-Z0-9_-]{11})/);
            if (match) return match[1];
            const hashMatch = href.match(/watch\?v=([a-zA-Z0-9_-]{11})/);
            if (hashMatch) return hashMatch[1];
        } catch (e) {}
        return null;
    }

    // ---------------------------------------------------------------------------
    // 5. ON-SCREEN VAGIIARK SETTINGS BUTTON
    // ---------------------------------------------------------------------------
    function injectSettingsButton() {
        if (document.getElementById('vagiiark-settings-btn')) return;
        const container = document.body;
        if (!container) return;

        const btn = document.createElement('div');
        btn.id = 'vagiiark-settings-btn';
        btn.tabIndex = 0;
        btn.title = "Open VagiiArk Settings";
        btn.style.cssText = `
            position: fixed;
            bottom: 24px;
            left: 24px;
            z-index: 99999;
            background: rgba(15, 20, 32, 0.90);
            border: 1.5px solid rgba(68, 138, 255, 0.45);
            border-radius: 20px;
            padding: 8px 18px;
            color: #FFFFFF;
            font-family: sans-serif;
            font-size: 13px;
            font-weight: bold;
            display: flex;
            align-items: center;
            gap: 8px;
            cursor: pointer;
            transition: all 0.15s ease;
            box-shadow: 0 4px 16px rgba(0,0,0,0.6);
        `;
        btn.innerHTML = `<span style="color:#00E5FF; font-size:15px;">⚡</span> <span>VagiiArk Menu</span>`;

        btn.addEventListener('focus', function () {
            btn.style.background = '#2979FF';
            btn.style.borderColor = '#FFFFFF';
            btn.style.transform = 'scale(1.08)';
        });
        btn.addEventListener('blur', function () {
            btn.style.background = 'rgba(15, 20, 32, 0.90)';
            btn.style.borderColor = 'rgba(68, 138, 255, 0.45)';
            btn.style.transform = 'scale(1.0)';
        });
        btn.addEventListener('click', function () {
            if (BRIDGE && BRIDGE.openQuickMenuFromJs) {
                BRIDGE.openQuickMenuFromJs();
            }
        });
        btn.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' || e.keyCode === 13 || e.keyCode === 23) {
                if (BRIDGE && BRIDGE.openQuickMenuFromJs) {
                    BRIDGE.openQuickMenuFromJs();
                }
                e.preventDefault();
                e.stopPropagation();
            }
        });

        container.appendChild(btn);
    }

    // ---------------------------------------------------------------------------
    // 6. EVENT DELEGATION
    // ---------------------------------------------------------------------------
    document.addEventListener('play', function (e) {
        if (e.target && e.target.tagName === 'VIDEO') {
            const video = e.target;
            enforceSpeed(video);
            const vId = extractVideoId();
            if (vId && vId !== currentVideoId) {
                currentVideoId = vId;
                applyInitialQuality(vId);
                fetchSponsorSegments(vId);
            }
        }
    }, true);

    document.addEventListener('timeupdate', function (e) {
        if (e.target && e.target.tagName === 'VIDEO') {
            checkSponsorBlock(e.target);
        }
    }, true);

    setInterval(function () {
        injectSettingsButton();
        const video = document.querySelector('video');
        if (video) {
            enforceSpeed(video);
            const vId = extractVideoId();
            if (vId && vId !== currentVideoId) {
                currentVideoId = vId;
                applyInitialQuality(vId);
                fetchSponsorSegments(vId);
            }
        }
    }, 1000);

    // Dynamic config update from Java
    window.__vagiiArkUpdateConfig = function (newConfig) {
        if (!newConfig) return;
        Object.assign(config, newConfig);
        if (newConfig.categories) Object.assign(config.categories, newConfig.categories);
        applyCustomCss();
        const video = document.querySelector('video');
        if (video) enforceSpeed(video);
    };

})();
