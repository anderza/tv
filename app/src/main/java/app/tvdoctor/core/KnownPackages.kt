package app.tvdoctor.core

enum class Category(val label: String) {
    ADS("Реклама"),
    TELEMETRY("Телеметрия"),
    BLOAT("Лишнее"),
    LAUNCHER("Лаунчер"),
    VOICE("Голос/микрофон"),
    CAUTION("Осторожно"),
}

/** [safe] = disabling it is not known to break the TV's basic functions. */
data class Known(val pkg: String, val category: Category, val safe: Boolean, val note: String)

/**
 * Curated knowledge about preinstalled packages on Android TV / Google TV / Fire TV / Xiaomi boxes.
 * Only packages we are reasonably sure about are listed; everything else goes through [heuristic].
 */
object KnownPackages {

    private val list = listOf(
        // Google TV / Android TV
        Known("com.google.android.tvrecommendations", Category.ADS, true, "Рекомендации и промо-ряды на главном экране"),
        Known("com.google.android.leanbacklauncher.recommendations", Category.ADS, true, "Рекомендации старого лаунчера Android TV"),
        Known("com.google.android.adservices.api", Category.ADS, false, "Рекламные сервисы Privacy Sandbox (на части прошивок не отключается)"),
        Known("com.google.android.feedback", Category.TELEMETRY, true, "Отправка отзывов и отчётов в Google"),
        Known("com.google.android.partnersetup", Category.BLOAT, false, "Партнёрские настройки Google"),
        Known("com.google.android.backdrop", Category.BLOAT, true, "Фоновый режим / заставка Google (тратит сеть и память)"),
        Known("com.google.android.apps.tv.dreamx", Category.BLOAT, true, "Фоновый режим Google TV (заставка с фото)"),
        Known("com.google.android.play.games", Category.BLOAT, true, "Google Play Игры"),
        Known("com.google.android.videos", Category.BLOAT, true, "Google TV / Play Фильмы"),
        Known("com.google.android.youtube.tvmusic", Category.BLOAT, true, "YouTube Music"),
        Known("com.google.android.youtube.tvkids", Category.BLOAT, true, "YouTube Kids"),
        Known("com.google.android.marvin.talkback", Category.BLOAT, true, "TalkBack (озвучивание экрана)"),
        Known("com.google.android.onetimeinitializer", Category.BLOAT, true, "Одноразовая инициализация после настройки"),
        Known("com.google.android.apps.mediashell", Category.CAUTION, false, "Chromecast built-in: без него не будет трансляции с телефона"),
        Known("com.google.android.tv.remote.service", Category.CAUTION, false, "Управление ТВ с телефона (Google TV Remote)"),
        Known("com.google.android.katniss", Category.VOICE, false, "Голосовой поиск и Ассистент (микрофон пульта)"),
        Known("com.google.android.speech.pumpkin", Category.VOICE, false, "Офлайн-распознавание голосовых команд"),
        Known("com.google.android.tts", Category.CAUTION, false, "Синтез речи Google"),
        Known("com.google.android.apps.tv.launcherx", Category.LAUNCHER, false, "Лаунчер Google TV (показывает рекламу). Отключать только после установки другого лаунчера"),
        Known("com.google.android.tvlauncher", Category.LAUNCHER, false, "Лаунчер Android TV (ряд «Спонсорский контент»). Отключать только после установки другого лаунчера"),
        Known("com.google.android.leanbacklauncher", Category.LAUNCHER, false, "Старый лаунчер Android TV"),
        // AOSP leftovers useless on a TV
        Known("com.android.printspooler", Category.BLOAT, true, "Служба печати"),
        Known("com.android.bips", Category.BLOAT, true, "Встроенная служба печати"),
        Known("com.android.dreams.basic", Category.BLOAT, true, "Базовая заставка"),
        Known("com.android.dreams.phototable", Category.BLOAT, true, "Заставка «Фотостол»"),
        Known("com.android.egg", Category.BLOAT, true, "Пасхалка Android"),
        Known("com.android.providers.partnerbookmarks", Category.BLOAT, true, "Партнёрские закладки"),
        Known("com.android.bookmarkprovider", Category.BLOAT, true, "Закладки браузера"),
        Known("com.android.wallpaper.livepicker", Category.BLOAT, true, "Выбор живых обоев"),
        Known("com.android.stk", Category.BLOAT, true, "SIM-меню (на ТВ не нужно)"),
        Known("com.android.cellbroadcastreceiver", Category.BLOAT, true, "Экстренные оповещения сотовой сети"),
        Known("com.android.calllogbackup", Category.BLOAT, true, "Резервная копия журнала звонков"),
        Known("com.android.traceur", Category.BLOAT, true, "Системная трассировка (для разработчиков)"),
        // Facebook / partner preloads
        Known("com.facebook.appmanager", Category.TELEMETRY, true, "Предустановленный менеджер Facebook"),
        Known("com.facebook.services", Category.TELEMETRY, true, "Фоновые службы Facebook"),
        Known("com.facebook.system", Category.TELEMETRY, true, "Установщик Facebook"),
        Known("com.netflix.partner.activation", Category.TELEMETRY, true, "Партнёрская активация Netflix (сам Netflix не затрагивается)"),
        // Xiaomi Mi Box / Mi TV
        Known("com.miui.systemAdSolution", Category.ADS, true, "Рекламный сервис Xiaomi"),
        Known("com.miui.analytics", Category.TELEMETRY, true, "Аналитика Xiaomi"),
        Known("com.mitv.tvhome", Category.LAUNCHER, false, "Лаунчер PatchWall (Xiaomi)"),
        // Amazon Fire TV
        Known("com.amazon.device.crashmanager", Category.TELEMETRY, true, "Отчёты о сбоях Amazon"),
        Known("com.amazon.dp.logger", Category.TELEMETRY, true, "Логгер Amazon"),
        Known("com.amazon.client.metrics", Category.TELEMETRY, false, "Метрики Amazon (на некоторых прошивках нужен для входа)"),
        Known("com.amazon.client.metrics.api", Category.TELEMETRY, false, "API метрик Amazon"),
        Known("com.amazon.tv.forcedotaupdater.v2", Category.CAUTION, false, "Принудительные обновления Fire TV"),
        Known("com.amazon.device.software.ota", Category.CAUTION, false, "Обновления системы Fire TV"),
        Known("com.amazon.tv.launcher", Category.LAUNCHER, false, "Лаунчер Fire TV"),
    ).associateBy { it.pkg }

    fun get(pkg: String): Known? = list[pkg]
    val all: Collection<Known> get() = list.values

    /** Never touch these without expert mode: the TV may stop booting or lose its UI. */
    private val protectedExact = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.tv.settings", "com.android.shell",
        "com.android.phone", "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.android.bluetooth",
        "com.android.inputdevices", "com.android.location.fused", "com.android.externalstorage",
        "com.android.keychain", "com.android.certinstaller", "com.android.server.telecom",
        "com.google.android.ext.services", "com.android.ext.services", "com.google.android.modulemetadata",
        "com.android.webview", "com.google.android.webview", "com.google.android.trichromelibrary",
        "com.google.android.tungsten.setupwraith", "com.google.android.tvsetup", "com.android.tv.provision",
        "com.google.android.leanback.ime", "com.google.android.inputmethod.latin", "com.android.inputmethod.latin",
        "com.android.wallpaperbackup", "com.android.sharedstoragebackup", "com.android.backupconfirm",
        "com.android.mtp", "com.android.localtransport", "com.android.statementservice",
        "com.android.media.tv.remoteprovider", "com.android.tv", "com.android.tv.frameworkpackagestubs",
        "com.google.android.apps.tv.launcherx.coreservices", "com.google.android.sss",
    )
    private val protectedPrefixes = listOf(
        "com.android.providers.", "com.android.networkstack", "com.google.android.networkstack",
        "com.android.wifi", "com.google.android.wifi", "com.android.server.", "com.android.cts.",
        "com.qualcomm.", "com.qti.", "vendor.", "com.mediatek.", "com.realtek.", "com.amlogic.", "com.droidlogic.",
        "com.rockchip.", "com.android.se", "com.google.android.overlay", "com.android.internal.",
        "com.google.android.ext.", "com.android.adservices",
    )

    fun isProtected(pkg: String, currentLauncher: String? = null): Boolean =
        pkg in protectedExact || protectedPrefixes.any { pkg.startsWith(it) } || pkg == currentLauncher ||
            pkg.endsWith(".overlay") || pkg.contains(".overlay.")

    private val heuristicRe = Regex(
        "(^|[._])(ad|ads|adservice|adsdk|adsolution|advert|advertise|advertising|analytics|telemetry|tracker|tracking|" +
            "statistic|statistics|stats|logger|crashreport|feedback|metrics|promo|promotion|dataservice|bigdata|userexperience)([._]|$)",
        RegexOption.IGNORE_CASE,
    )

    /** Package names that look like ads/telemetry but are not in the curated list. Needs review, not auto-selected. */
    fun heuristic(pkg: String): Boolean = get(pkg) == null && !isProtected(pkg) && heuristicRe.containsMatchIn(pkg)

    /** Well-known Private DNS servers that block ads and trackers (DNS-over-TLS hostnames). */
    val adBlockDns = listOf(
        "dns.adguard-dns.com" to "AdGuard — блокирует рекламу и трекеры",
        "family.adguard-dns.com" to "AdGuard Семейный — реклама + взрослый контент",
        "adblock.dns.mullvad.net" to "Mullvad — блокировка рекламы",
        "p2.freedns.controld.com" to "Control D — реклама и трекеры",
    )
}
