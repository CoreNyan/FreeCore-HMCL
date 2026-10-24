/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.upgrade;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.EntryPoint;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.SwingUtils;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.HttpRequest;
import org.jackhuang.hmcl.util.io.JarUtils;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.util.versioning.VersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Loads FreeCore repository configuration and applies mandatory launcher releases before JavaFX starts.
@NotNullByDefault
public final class FreeCoreBootstrap {
    /// Built-in authentication endpoint used when the repository configuration is unavailable.
    public static final String BUILTIN_AUTH_SERVER_URL =
            "https://account.freecore.cc/api/yggdrasil/";

    /// Built-in web login endpoint used when the repository configuration is unavailable.
    public static final String BUILTIN_LOGIN_URL =
            "https://account.freecore.cc/auth/login";

    /// Built-in user center endpoint used when the repository configuration is unavailable.
    public static final String BUILTIN_USER_CENTER_URL =
            "https://account.freecore.cc/user";

    /// Built-in documentation endpoint used when the repository configuration is unavailable.
    public static final String BUILTIN_DOCS_URL =
            "https://docs.freecore.cc";

    /// Built-in official QQ group link used when the repository configuration is unavailable.
    public static final String BUILTIN_GROUP_URL =
            "https://qm.qq.com/q/fwCibn5mjC";

    /// Built-in official website endpoint used when the repository configuration is unavailable.
    public static final String BUILTIN_OFFICIAL_WEBSITE_URL =
            "https://freecore.cc/";

    /// bdnb.cn API endpoint returning FreeCore releases ordered newest first.
    private static final String LATEST_RELEASE_URL =
            "https://bdnb.cn/api/repos/CoreNyan/FreeCore-HMCL/releases?page=1&per_page=1";

    /// Redundant high-availability endpoints for the repository configuration.
    private static final String[] REMOTE_CONFIG_URLS = {
            "https://cdn.jsdelivr.net/gh/CoreNyan/FreeCore-HMCL@main/freecore-launcher.json",
            "https://fastly.jsdelivr.net/gh/CoreNyan/FreeCore-HMCL@main/freecore-launcher.json",
            "https://testingcf.jsdelivr.net/gh/CoreNyan/FreeCore-HMCL@main/freecore-launcher.json",
            "https://ghproxy.net/https://raw.githubusercontent.com/CoreNyan/FreeCore-HMCL/main/freecore-launcher.json",
            "https://raw.githubusercontent.com/CoreNyan/FreeCore-HMCL/main/freecore-launcher.json"
    };

    /// GitHub download proxy used for release assets in mainland China.
    private static final String GITHUB_PROXY_URL = "https://ghproxy.net/";

    /// Local state recording the last repository authentication URL applied to account storage.
    private static final Path REMOTE_CONFIG_STATE_FILE =
            Metadata.HMCL_LOCAL_HOME.resolve("state").resolve("freecore-remote-config.json");

    /// Legacy Windows executable asset name used by early FreeCore releases.
    private static final String LEGACY_WINDOWS_ASSET_NAME = "FreeCore-Launcher.exe";

    /// Environment marker used to avoid rechecking the same version directly after replacement.
    private static final String UPDATED_VERSION_ENVIRONMENT = "FREECORE_UPDATED_TO";

    /// Command-line marker for the copied launcher process that applies a staged update.
    private static final String UPDATE_HELPER_ARGUMENT = "--freecore-apply-update";

    /// Current configured authentication endpoint.
    private static volatile String defaultAuthServerUrl = BUILTIN_AUTH_SERVER_URL;

    /// Current configured web login endpoint.
    private static volatile String loginUrl = BUILTIN_LOGIN_URL;

    /// Current configured user center endpoint.
    private static volatile String userCenterUrl = BUILTIN_USER_CENTER_URL;

    /// Current configured documentation endpoint.
    private static volatile String docsUrl = BUILTIN_DOCS_URL;

    /// Current configured official QQ group link.
    private static volatile String groupUrl = BUILTIN_GROUP_URL;

    /// Current configured official website endpoint.
    private static volatile String officialWebsiteUrl = BUILTIN_OFFICIAL_WEBSITE_URL;

    /// Previous fixed authentication endpoint when the repository configuration changed.
    private static volatile @Nullable String previousAuthServerUrl;

    /// Whether the fixed FreeCore authentication endpoint changed since it was last applied.
    private static volatile boolean authServerChanged;

    /// Whether the repository configuration was successfully loaded during this startup.
    private static volatile boolean remoteConfigurationLoaded;

    /// Whether the JavaFX application must open the fixed FreeCore login dialog.
    private static volatile boolean authenticationReloginRequired;

    /// Prevents construction of the startup utility.
    private FreeCoreBootstrap() {
    }

    /// Runs remote configuration and the mandatory GitHub release check.
    ///
    /// @return true when normal startup should continue, or false after an updater process takes over
    public static boolean run() {
        refreshRemoteConfiguration();

        try {
            return checkMandatoryUpdate();
        } catch (MandatoryUpdateException e) {
            LOG.warning("The mandatory FreeCore launcher update could not be applied", e);
            showMandatoryUpdateFailure(e.getMessage());
            return false;
        } catch (Throwable e) {
            LOG.warning("Failed to check for a mandatory FreeCore launcher update; falling back to normal startup", e);
            // Do not block user from launching when mirror or GitHub API is down.
            return true;
        }
    }

    /// Applies a staged Windows update when this copied launcher is running as the helper process.
    public static void runUpdaterHelperIfRequested(String[] args) {
        if (args.length == 0 || !UPDATE_HELPER_ARGUMENT.equals(args[0])) {
            return;
        }

        try {
            if (args.length != 6) {
                throw new IOException("Invalid FreeCore update helper arguments");
            }
            Path downloadedExecutable = Path.of(args[1]);
            Path targetExecutable = Path.of(args[2]);
            long processId = Long.parseLong(args[3]);
            String version = args[4];
            Path updaterLog = Path.of(args[5]);
            appendUpdaterLog(updaterLog, "Update helper started for version " + version);

            while (ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false)) {
                Thread.sleep(500);
            }

            appendUpdaterLog(updaterLog, "Replacing launcher executable");
            Files.move(downloadedExecutable, targetExecutable, StandardCopyOption.REPLACE_EXISTING);
            appendUpdaterLog(updaterLog, "Replacement succeeded; restarting launcher");
            ProcessBuilder restart = new ProcessBuilder(targetExecutable.toString())
                    .directory(targetExecutable.getParent().toFile());
            restart.environment().put(UPDATED_VERSION_ENVIRONMENT, version);
            restart.start();
            EntryPoint.exit(0);
        } catch (Exception e) {
            LOG.warning("Failed to apply FreeCore launcher update", e);
            SwingUtils.initLookAndFeel();
            JOptionPane.showMessageDialog(
                    null,
                    "FreeCore 更新安装失败。更新文件和日志已保留：\n"
                            + Metadata.HMCL_LOCAL_HOME.resolve("update")
                            + "\n\n" + Objects.toString(e.getMessage(), e.getClass().getSimpleName()),
                    "FreeCore 更新失败",
                    JOptionPane.ERROR_MESSAGE);
            EntryPoint.exit(1);
        }
    }

    /// Returns the authentication endpoint loaded from the repository configuration.
    public static String getDefaultAuthServerUrl() {
        return defaultAuthServerUrl;
    }

    /// Returns the web login URL loaded from the repository configuration or default.
    public static String getLoginUrl() {
        return loginUrl;
    }

    /// Returns the user center URL loaded from the repository configuration or default.
    public static String getUserCenterUrl() {
        return userCenterUrl;
    }

    /// Returns the documentation URL loaded from the repository configuration or default.
    public static String getDocsUrl() {
        return docsUrl;
    }

    /// Returns the official QQ group link loaded from the repository configuration or default.
    public static String getGroupUrl() {
        return groupUrl;
    }

    /// Returns the official website URL loaded from the repository configuration or default.
    public static String getOfficialWebsiteUrl() {
        return officialWebsiteUrl;
    }

    /// Returns the previous fixed authentication endpoint when a migration is pending.
    public static @Nullable String getPreviousAuthServerUrl() {
        return previousAuthServerUrl;
    }

    /// Returns whether the repository changed the fixed FreeCore authentication endpoint.
    public static boolean isAuthServerChanged() {
        return authServerChanged;
    }

    /// Returns whether this startup successfully loaded the repository configuration.
        /// Starts a non-daemon or timer task to refresh configuration periodically while running.
    public static void startBackgroundConfigurationSync() {
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(10 * 60 * 1000L); // 10 minutes
                    LOG.info("Running periodic FreeCore remote configuration sync...");
                    refreshRemoteConfiguration();
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable e) {
                    LOG.warning("Background FreeCore configuration sync encountered an error", e);
                }
            }
        }, "FreeCore-Config-Sync");
        thread.setDaemon(true);
        thread.start();
    }

    public static boolean isRemoteConfigurationLoaded() {
        return remoteConfigurationLoaded;
    }

    /// Requests that the application open the fixed FreeCore login dialog after startup.
    public static void requireAuthenticationRelogin() {
        authenticationReloginRequired = true;
    }

    /// Consumes the pending request to open the fixed FreeCore login dialog.
    public static boolean consumeAuthenticationReloginRequired() {
        boolean required = authenticationReloginRequired;
        authenticationReloginRequired = false;
        return required;
    }

    /// Records that account storage has been migrated to the current repository authentication URL.
    ///
    /// @throws IOException if the applied configuration state cannot be persisted
    public static void markAuthenticationServerMigrationApplied() throws IOException {
        writeRemoteConfigurationState(defaultAuthServerUrl);
        previousAuthServerUrl = null;
        authServerChanged = false;
    }

    /// Downloads and validates the repository configuration from redundant mirrors, retaining defaults on failure.
    public static void refreshRemoteConfiguration() {
        remoteConfigurationLoaded = false;
        JsonObject fetchedRoot = null;
        Throwable lastError = null;

        for (String remoteUrl : REMOTE_CONFIG_URLS) {
            try {
                String requestUrl = remoteUrl + (remoteUrl.contains("?") ? "&" : "?") + "ts=" + System.currentTimeMillis();
                JsonObject root = JsonUtils.fromNonNullJson(
                        HttpRequest.GET(requestUrl)
                                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                                .header("Pragma", "no-cache")
                                .timeout(Duration.ofSeconds(4))
                                .retry(1)
                                .getString(),
                        JsonObject.class);
                String configuredUrl = JsonUtils.getString(root, "defaultAuthServerUrl");
                if (configuredUrl != null && !configuredUrl.isBlank()) {
                    fetchedRoot = root;
                    LOG.info("Successfully fetched FreeCore remote configuration from " + remoteUrl);
                    break;
                }
            } catch (Throwable e) {
                lastError = e;
                LOG.warning("Failed to load FreeCore remote configuration from mirror " + remoteUrl, e);
            }
        }

        if (fetchedRoot != null) {
            try {
                String configuredUrl = JsonUtils.getString(fetchedRoot, "defaultAuthServerUrl");
                if (configuredUrl == null || configuredUrl.isBlank()) {
                    throw new JsonParseException("defaultAuthServerUrl is missing");
                }

                defaultAuthServerUrl = normalizeAuthServerUrl(configuredUrl);

                String parsedLogin = JsonUtils.getString(fetchedRoot, "loginUrl");
                if (parsedLogin != null && !parsedLogin.isBlank()) {
                    loginUrl = parsedLogin.trim();
                }

                String parsedUserCenter = JsonUtils.getString(fetchedRoot, "userCenterUrl");
                if (parsedUserCenter != null && !parsedUserCenter.isBlank()) {
                    userCenterUrl = parsedUserCenter.trim();
                }

                String parsedDocs = JsonUtils.getString(fetchedRoot, "docsUrl");
                if (parsedDocs != null && !parsedDocs.isBlank()) {
                    docsUrl = parsedDocs.trim();
                }

                String parsedGroup = JsonUtils.getString(fetchedRoot, "groupUrl");
                if (parsedGroup != null && !parsedGroup.isBlank()) {
                    groupUrl = parsedGroup.trim();
                }

                String parsedWebsite = JsonUtils.getString(fetchedRoot, "officialWebsiteUrl");
                if (parsedWebsite != null && !parsedWebsite.isBlank()) {
                    officialWebsiteUrl = normalizeOfficialWebsiteUrl(parsedWebsite);
                }

                remoteConfigurationLoaded = true;
                updateRemoteConfigurationState(defaultAuthServerUrl);
                LOG.info("Applied FreeCore remote configuration: defaultAuthServerUrl=" + defaultAuthServerUrl
                        + ", loginUrl=" + loginUrl + ", userCenterUrl=" + userCenterUrl
                        + ", docsUrl=" + docsUrl + ", officialWebsiteUrl=" + officialWebsiteUrl);
                return;
            } catch (Exception e) {
                lastError = e;
                LOG.warning("Failed to parse fetched FreeCore remote configuration", e);
            }
        }

        previousAuthServerUrl = null;
        authServerChanged = false;
        try {
            defaultAuthServerUrl = readAppliedAuthenticationServerUrl();
            readAppliedAdditionalUrls();
            LOG.warning("Failed to load FreeCore remote configuration from all sources; using the last applied URL", lastError);
        } catch (Exception stateError) {
            defaultAuthServerUrl = BUILTIN_AUTH_SERVER_URL;
            loginUrl = BUILTIN_LOGIN_URL;
            userCenterUrl = BUILTIN_USER_CENTER_URL;
            docsUrl = BUILTIN_DOCS_URL;
            groupUrl = BUILTIN_GROUP_URL;
            officialWebsiteUrl = BUILTIN_OFFICIAL_WEBSITE_URL;
            if (lastError != null) {
                stateError.addSuppressed(lastError);
            }
            LOG.warning("Failed to load FreeCore remote configuration and local state; "
                    + "using the built-in URL", stateError);
        }
    }

    /// Compares the fetched URL with the last successfully applied repository configuration.
    private static void updateRemoteConfigurationState(String configuredUrl) {
        if (!Files.isRegularFile(REMOTE_CONFIG_STATE_FILE)) {
            try {
                writeRemoteConfigurationState(configuredUrl);
            } catch (IOException e) {
                LOG.warning("Failed to initialize FreeCore remote configuration state", e);
            }
            return;
        }

        try {
            String normalizedAppliedUrl = readAppliedAuthenticationServerUrl();
            if (!normalizedAppliedUrl.equals(configuredUrl)) {
                previousAuthServerUrl = normalizedAppliedUrl;
                authServerChanged = true;
                LOG.info("FreeCore authentication endpoint changed: "
                        + normalizedAppliedUrl + " -> " + configuredUrl);
            }
        } catch (Exception e) {
            LOG.warning("Failed to read FreeCore remote configuration state", e);
            try {
                writeRemoteConfigurationState(configuredUrl);
            } catch (IOException writeError) {
                LOG.warning("Failed to repair FreeCore remote configuration state", writeError);
            }
        }
    }

    /// Reads and validates the last repository authentication URL applied to account storage.
    ///
    /// @return the normalized authentication URL from local state
    /// @throws IOException if the state file cannot be read
    /// @throws JsonParseException if the state does not contain a valid authentication URL
    private static String readAppliedAuthenticationServerUrl() throws IOException {
        JsonObject state = JsonUtils.fromNonNullJson(
                Files.readString(REMOTE_CONFIG_STATE_FILE, StandardCharsets.UTF_8),
                JsonObject.class);
        @Nullable String appliedUrl = JsonUtils.getString(state, "defaultAuthServerUrl");
        if (appliedUrl == null || appliedUrl.isBlank()) {
            throw new JsonParseException("defaultAuthServerUrl is missing from local state");
        }
        return normalizeAuthServerUrl(appliedUrl);
    }

    /// Reads cached additional endpoints from local state if available.
    private static void readAppliedAdditionalUrls() {
        try {
            if (!Files.isRegularFile(REMOTE_CONFIG_STATE_FILE)) {
                return;
            }
            JsonObject state = JsonUtils.fromNonNullJson(
                    Files.readString(REMOTE_CONFIG_STATE_FILE, StandardCharsets.UTF_8),
                    JsonObject.class);
            String cachedLogin = JsonUtils.getString(state, "loginUrl");
            if (cachedLogin != null && !cachedLogin.isBlank()) {
                loginUrl = cachedLogin.trim();
            }
            String cachedUserCenter = JsonUtils.getString(state, "userCenterUrl");
            if (cachedUserCenter != null && !cachedUserCenter.isBlank()) {
                userCenterUrl = cachedUserCenter.trim();
            }
            String cachedDocs = JsonUtils.getString(state, "docsUrl");
            if (cachedDocs != null && !cachedDocs.isBlank()) {
                docsUrl = cachedDocs.trim();
            }
            String cachedGroup = JsonUtils.getString(state, "groupUrl");
            if (cachedGroup != null && !cachedGroup.isBlank()) {
                groupUrl = cachedGroup.trim();
            }
            String cachedWebsite = JsonUtils.getString(state, "officialWebsiteUrl");
            if (cachedWebsite != null && !cachedWebsite.isBlank()) {
                officialWebsiteUrl = normalizeOfficialWebsiteUrl(cachedWebsite);
            }
        } catch (Exception e) {
            LOG.warning("Failed to read cached FreeCore endpoint URLs from local state", e);
        }
    }

    /// Atomically persists the repository authentication and extended URLs to local state.
    private static void writeRemoteConfigurationState(String configuredUrl) throws IOException {
        Files.createDirectories(REMOTE_CONFIG_STATE_FILE.getParent());
        JsonObject state = new JsonObject();
        state.addProperty("defaultAuthServerUrl", configuredUrl);
        state.addProperty("loginUrl", loginUrl);
        state.addProperty("userCenterUrl", userCenterUrl);
        state.addProperty("docsUrl", docsUrl);
        state.addProperty("groupUrl", groupUrl);
        state.addProperty("officialWebsiteUrl", officialWebsiteUrl);

        Path temporary = REMOTE_CONFIG_STATE_FILE.resolveSibling(
                REMOTE_CONFIG_STATE_FILE.getFileName() + ".tmp");
        Files.writeString(
                temporary,
                JsonUtils.GSON.toJson(state),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(
                    temporary,
                    REMOTE_CONFIG_STATE_FILE,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, REMOTE_CONFIG_STATE_FILE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /// Checks the latest GitHub release and stages replacement when a newer version exists.
    private static boolean checkMandatoryUpdate() throws IOException {
        if (OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS) {
            return true;
        }

        Path currentExecutable = JarUtils.thisJarPath();
        if (currentExecutable == null
                || !currentExecutable.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe")) {
            LOG.info("Skipping FreeCore executable update outside a Windows EXE");
            return true;
        }

        Release release = fetchLatestRelease();
        if (release.version().equals(System.getenv(UPDATED_VERSION_ENVIRONMENT))) {
            return true;
        }

        if (VersionNumber.compare(normalizeVersion(Metadata.VERSION), release.version()) >= 0) {
            return true;
        }

        if ((release.mirrorExecutableUrl() == null && release.githubExecutableUrl() == null)
                || (release.mirrorChecksumUrl() == null && release.githubChecksumUrl() == null)) {
            throw new MandatoryUpdateException(
                    "FreeCore " + release.version() + " 缺少启动器更新文件，请联系服务器管理员。",
                    null);
        }

        LOG.info("Mandatory FreeCore update available: " + Metadata.VERSION + " -> " + release.version());
        UpdateProgressDialog progressDialog = showUpdateProgress(release.version());
        try {
            stageWindowsUpdate(currentExecutable, release, progressDialog);
        } catch (IOException e) {
            progressDialog.close();
            throw new MandatoryUpdateException(
                    "FreeCore " + release.version() + " 更新下载或安装失败，请检查网络和文件权限后重试。",
                    e);
        }
        return false;
    }

    /// Fetches and parses the newest mirrored release and its mandatory update assets.
    private static Release fetchLatestRelease() throws IOException {
        JsonObject releaseObject = null;
        try {
            JsonArray ghArray = JsonUtils.fromNonNullJson(
                HttpRequest.GET("https://api.github.com/repos/CoreNyan/FreeCore-HMCL/releases?page=1&per_page=1")
                    .accept("application/json")
                    .timeout(Duration.ofSeconds(4))
                    .getString(),
                JsonArray.class);
            if (ghArray != null && !ghArray.isEmpty() && ghArray.get(0).isJsonObject()) {
                releaseObject = ghArray.get(0).getAsJsonObject();
            }
        } catch (Throwable ghErr) {
            LOG.warning("Failed to fetch release from GitHub API; trying mirror", ghErr);
        }

        if (releaseObject == null) {
            try {
                JsonObject response = JsonUtils.fromNonNullJson(
                    HttpRequest.GET(LATEST_RELEASE_URL)
                        .accept("application/json")
                        .timeout(Duration.ofSeconds(2))
                        .getString(),
                    JsonObject.class);
                JsonArray releases = response.getAsJsonArray("data");
                if (releases != null && !releases.isEmpty() && releases.get(0).isJsonObject()) {
                    releaseObject = releases.get(0).getAsJsonObject();
                }
            } catch (Throwable mirrorErr) {
                LOG.warning("FreeCore mirror release fetch failed", mirrorErr);
            }
        }

        if (releaseObject == null) {
            throw new JsonParseException("All release sources failed");
        }

        String tagName = JsonUtils.getString(releaseObject, "tag_name");
        if (tagName == null || tagName.isBlank()) {
            throw new JsonParseException("Latest GitHub release does not contain tag_name");
        }

        JsonArray assets = releaseObject.getAsJsonArray("assets");
        if (assets == null) {
            throw new JsonParseException("Latest GitHub release does not contain assets");
        }

        String version = normalizeVersion(tagName);
        // Current build tasks publish HMCL-{version}.exe; keep the old name as a
        // fallback so releases made before the naming migration remain installable.
        String versionedAssetName = "HMCL-" + version + ".exe";
        String executableAssetName = findAssetName(assets, versionedAssetName);
        if (executableAssetName == null) {
            executableAssetName = findAssetName(assets, LEGACY_WINDOWS_ASSET_NAME);
        }
        String checksumAssetName = executableAssetName == null ? null : executableAssetName + ".sha256";
        String githubExecutableUrl = findAssetUrl(assets, executableAssetName);
        String githubChecksumUrl = findAssetUrl(assets, checksumAssetName);
        return new Release(
                version,
                toMirrorAssetUrl(githubExecutableUrl),
                githubExecutableUrl,
                toMirrorAssetUrl(githubChecksumUrl),
                githubChecksumUrl);
    }

    /// Finds the first exact asset name present in a release response.
    private static @Nullable String findAssetName(JsonArray assets, String assetName) {
        return findAssetUrl(assets, assetName) == null ? null : assetName;
    }

    /// Finds the download URL of a named GitHub release asset.
    private static @Nullable String findAssetUrl(JsonArray assets, @Nullable String assetName) {
        if (assetName == null) {
            return null;
        }
        for (JsonElement element : assets) {
            if (!(element instanceof JsonObject asset)) {
                continue;
            }
            if (assetName.equals(JsonUtils.getString(asset, "name"))) {
                return JsonUtils.getString(asset, "browser_download_url");
            }
        }
        return null;
    }

    /// Rewrites a GitHub release asset URL to the ghproxy.net download proxy.
    private static @Nullable String toMirrorAssetUrl(@Nullable String assetUrl) {
        if (assetUrl == null) {
            return null;
        }
        return GITHUB_PROXY_URL + assetUrl;
    }

    /// Downloads, verifies, and hands the new executable to an external replacement script.
    private static void stageWindowsUpdate(
            Path currentExecutable,
            Release release,
            UpdateProgressDialog progressDialog) throws IOException {
        Path updateDirectory = Metadata.HMCL_LOCAL_HOME.resolve("update");
        Files.createDirectories(updateDirectory);
        Path downloadedExecutable = updateDirectory.resolve("FreeCore-Launcher.exe.download");
        Path updateHelper = updateDirectory.resolve("freecore-update-helper-" + release.version() + ".exe");
        Path updaterLog = updateDirectory.resolve("apply-freecore-update.log");

        progressDialog.setStatusAndWait("正在下载 FreeCore " + release.version() + "…");
        downloadFileWithFallback(
                release.mirrorExecutableUrl(),
                release.githubExecutableUrl(),
                downloadedExecutable,
                "启动器");
        progressDialog.setStatusAndWait("正在校验更新文件…");
        String expectedChecksum = readExpectedChecksumWithFallback(
                release.mirrorChecksumUrl(),
                release.githubChecksumUrl());
        String actualChecksum = DigestUtils.digestToString("SHA-256", downloadedExecutable);
        if (!actualChecksum.equalsIgnoreCase(expectedChecksum)) {
            Files.deleteIfExists(downloadedExecutable);
            throw new IOException("Downloaded FreeCore launcher checksum mismatch");
        }
        LOG.info("Verified FreeCore " + release.version() + " update: " + Files.size(downloadedExecutable)
                + " bytes, SHA-256 " + actualChecksum);

        Files.copy(currentExecutable, updateHelper, StandardCopyOption.REPLACE_EXISTING);

        progressDialog.setStatusAndWait("下载完成，正在交给系统更新；启动器即将关闭并自动重启…");
        new ProcessBuilder(
                updateHelper.toString(),
                UPDATE_HELPER_ARGUMENT,
                downloadedExecutable.toString(),
                currentExecutable.toString(),
                Long.toString(ProcessHandle.current().pid()),
                release.version(),
                updaterLog.toString())
                .directory(currentExecutable.getParent().toFile())
                .start();
        LOG.info("FreeCore update helper started for version " + release.version()
                + " at " + updateHelper);
    }

    /// Downloads an update asset without retaining it in memory.
    private static void downloadFileWithFallback(
            @Nullable String mirrorUrl,
            @Nullable String githubUrl,
            Path output,
            String assetDescription) throws IOException {
        IOException mirrorFailure = null;
        if (mirrorUrl != null) {
            try {
                LOG.info("Downloading FreeCore " + assetDescription + " from ghproxy.net: " + mirrorUrl);
                downloadFile(mirrorUrl, output);
                LOG.info("Downloaded FreeCore " + assetDescription + " from ghproxy.net: "
                        + Files.size(output) + " bytes");
                return;
            } catch (IOException e) {
                mirrorFailure = e;
                LOG.warning("FreeCore ghproxy.net download failed; trying GitHub direct URL", e);
            }
        }
        if (githubUrl != null) {
            try {
                LOG.info("Downloading FreeCore " + assetDescription + " from GitHub: " + githubUrl);
                downloadFile(githubUrl, output);
                LOG.info("Downloaded FreeCore " + assetDescription + " from GitHub: "
                        + Files.size(output) + " bytes");
                return;
            } catch (IOException e) {
                if (mirrorFailure != null) {
                    e.addSuppressed(mirrorFailure);
                }
                throw e;
            }
        }
        throw Objects.requireNonNullElseGet(
                mirrorFailure,
                () -> new IOException("No download URL is available for FreeCore " + assetDescription));
    }

    /// Downloads one update asset from a single HTTP URL into a temporary file.
    private static void downloadFile(String url, Path output) throws IOException {
        HttpURLConnection connection = HttpRequest.GET(url)
                .header("Accept", "application/octet-stream")
                .createConnection();
        connection = NetworkUtils.resolveConnection(connection);
        if (connection.getResponseCode() / 100 != 2) {
            throw new IOException("Failed to download FreeCore launcher: HTTP " + connection.getResponseCode());
        }

        Path temporaryOutput = output.resolveSibling(output.getFileName() + ".tmp");
        try (InputStream input = connection.getInputStream()) {
            Files.copy(input, temporaryOutput, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporaryOutput, output, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(temporaryOutput);
            throw e;
        } finally {
            connection.disconnect();
        }
    }

    /// Reads a SHA-256 sidecar, falling back from the mirror to GitHub when necessary.
    private static String readExpectedChecksumWithFallback(
            @Nullable String mirrorUrl,
            @Nullable String githubUrl) throws IOException {
        IOException mirrorFailure = null;
        if (mirrorUrl != null) {
            try {
                LOG.info("Reading FreeCore checksum from ghproxy.net: " + mirrorUrl);
                return parseExpectedChecksum(HttpRequest.GET(mirrorUrl).retry(2).getString());
            } catch (IOException e) {
                mirrorFailure = e;
                LOG.warning("FreeCore ghproxy.net checksum download failed; trying GitHub", e);
            }
        }
        if (githubUrl != null) {
            try {
                LOG.info("Reading FreeCore checksum from GitHub: " + githubUrl);
                return parseExpectedChecksum(HttpRequest.GET(githubUrl).retry(2).getString());
            } catch (IOException e) {
                if (mirrorFailure != null) {
                    e.addSuppressed(mirrorFailure);
                }
                throw e;
            }
        }
        throw Objects.requireNonNullElseGet(
                mirrorFailure,
                () -> new IOException("No checksum URL is available for the FreeCore update"));
    }

    /// Parses a conventional hexadecimal SHA-256 sidecar.
    private static String parseExpectedChecksum(String content) throws IOException {
        String checksum = content.trim().split("\\s+", 2)[0];
        if (checksum.length() != 64) {
            throw new IOException("Invalid FreeCore SHA-256 sidecar");
        }
        try {
            HexFormat.of().parseHex(checksum);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid FreeCore SHA-256 sidecar", e);
        }
        return checksum;
    }

    /// Appends one timestamped line to the persistent helper diagnostic log.
    private static void appendUpdaterLog(Path log, String message) throws IOException {
        Files.createDirectories(log.getParent());
        Files.writeString(
                log,
                java.time.Instant.now() + " " + message + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE);
    }

    /// Normalizes an authentication endpoint and requires HTTPS.
    private static String normalizeAuthServerUrl(String url) {
        String normalized = url.trim();
        if (!normalized.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IllegalArgumentException("FreeCore authentication endpoint must use HTTPS");
        }
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    /// Keeps the public website separate from the account portal, which requires an authenticated session.
    private static String normalizeOfficialWebsiteUrl(String url) {
        String normalized = url.trim();
        if (normalized.equalsIgnoreCase("https://account.lynnhma.xyz")
                || normalized.equalsIgnoreCase("https://account.lynnhma.xyz/")) {
            return BUILTIN_OFFICIAL_WEBSITE_URL;
        }
        return normalized;
    }

    /// Removes a conventional v prefix before version comparison.
    private static String normalizeVersion(String version) {
        String normalized = Objects.requireNonNull(version).trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            return normalized.substring(1);
        }
        return normalized;
    }

    /// Shows why a required update prevented this launcher version from starting.
    private static void showMandatoryUpdateFailure(@Nullable String message) {
        SwingUtils.initLookAndFeel();
        JOptionPane.showMessageDialog(
                null,
                Objects.requireNonNullElse(message, "FreeCore 强制更新失败，请稍后重试。"),
                "FreeCore 更新失败",
                JOptionPane.ERROR_MESSAGE);
    }

    /// Asks the user to retry a failed mandatory release check or exit the launcher.
    private static boolean showUpdateCheckRetry() {
        SwingUtils.initLookAndFeel();
        Object[] options = {"重试", "退出"};
        int result = JOptionPane.showOptionDialog(
                null,
                "无法连接 FreeCore 镜像更新服务。\n请检查网络或代理设置后重试。",
                "FreeCore 更新检查失败",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.ERROR_MESSAGE,
                null,
                options,
                options[0]);
        return result == 0;
    }

    /// Opens a visible progress window before the mandatory update download starts.
    private static UpdateProgressDialog showUpdateProgress(String version) {
        SwingUtils.initLookAndFeel();
        AtomicReference<UpdateProgressDialog> result = new AtomicReference<>();
        Runnable createDialog = () -> {
            JLabel title = new JLabel("发现 FreeCore " + version + " 强制更新", SwingConstants.CENTER);
            JLabel status = new JLabel("正在准备下载…", SwingConstants.CENTER);
            JProgressBar progress = new JProgressBar();
            progress.setIndeterminate(true);

            JPanel panel = new JPanel(new BorderLayout(0, 12));
            panel.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
            panel.add(title, BorderLayout.NORTH);
            panel.add(progress, BorderLayout.CENTER);
            panel.add(status, BorderLayout.SOUTH);

            JDialog dialog = new JDialog((Window) null, "FreeCore 正在更新");
            dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            dialog.setModal(false);
            dialog.setAlwaysOnTop(true);
            dialog.setContentPane(panel);
            dialog.setPreferredSize(new Dimension(420, 150));
            dialog.pack();
            dialog.setLocationRelativeTo(null);
            dialog.setVisible(true);
            result.set(new UpdateProgressDialog(dialog, status));
        };

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                createDialog.run();
            } else {
                SwingUtilities.invokeAndWait(createDialog);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to display the FreeCore update progress", e);
        }
        return Objects.requireNonNull(result.get());
    }

    /// Parsed mandatory release assets.
    ///
    /// @param version normalized release version
    /// @param mirrorExecutableUrl Windows executable URL through ghproxy.net
    /// @param githubExecutableUrl direct GitHub Windows executable URL
    /// @param mirrorChecksumUrl SHA-256 sidecar URL through ghproxy.net
    /// @param githubChecksumUrl direct GitHub SHA-256 sidecar URL
    private record Release(
            String version,
            @Nullable String mirrorExecutableUrl,
            @Nullable String githubExecutableUrl,
            @Nullable String mirrorChecksumUrl,
            @Nullable String githubChecksumUrl) {
    }

    /// Signals that a discovered newer release must be installed before startup may continue.
    private static final class MandatoryUpdateException extends IOException {
        /// Creates a blocking update error with an optional underlying I/O failure.
        private MandatoryUpdateException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    /// Swing window that reports mandatory update progress before the launcher exits.
    private static final class UpdateProgressDialog {
        /// Visible update dialog.
        private final JDialog dialog;

        /// Text describing the current update stage.
        private final JLabel status;

        /// Creates a progress window wrapper around its Swing controls.
        private UpdateProgressDialog(JDialog dialog, JLabel status) {
            this.dialog = dialog;
            this.status = status;
        }

        /// Replaces the current progress message on the Swing event thread.
        private void setStatus(String text) {
            SwingUtilities.invokeLater(() -> status.setText(text));
        }

        /// Replaces the current progress message and waits until Swing paints the change.
        private void setStatusAndWait(String text) {
            try {
                if (SwingUtilities.isEventDispatchThread()) {
                    status.setText(text);
                } else {
                    SwingUtilities.invokeAndWait(() -> status.setText(text));
                }
            } catch (Exception e) {
                LOG.warning("Failed to update FreeCore update progress", e);
                setStatus(text);
            }
        }

        /// Closes the progress window after a failed update attempt.
        private void close() {
            SwingUtilities.invokeLater(dialog::dispose);
        }
    }
}
