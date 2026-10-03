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
            "https://account.lynnhma.xyz/api/yggdrasil/";

    /// GitHub API endpoint for the newest published FreeCore launcher release.
    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/CoreNyan/FreeCore-HMCL/releases/latest";

    /// Raw repository configuration read on every launcher start.
    private static final String REMOTE_CONFIG_URL =
            "https://raw.githubusercontent.com/CoreNyan/FreeCore-HMCL/main/freecore-launcher.json";

    /// Local state recording the last repository authentication URL applied to account storage.
    private static final Path REMOTE_CONFIG_STATE_FILE =
            Metadata.HMCL_LOCAL_HOME.resolve("state").resolve("freecore-remote-config.json");

    /// Windows executable asset required in every launcher release.
    private static final String WINDOWS_ASSET_NAME = "FreeCore-Launcher.exe";

    /// SHA-256 sidecar required beside the Windows executable asset.
    private static final String WINDOWS_CHECKSUM_ASSET_NAME = WINDOWS_ASSET_NAME + ".sha256";

    /// Environment marker used to avoid rechecking the same version directly after replacement.
    private static final String UPDATED_VERSION_ENVIRONMENT = "FREECORE_UPDATED_TO";

    /// Current configured authentication endpoint.
    private static volatile String defaultAuthServerUrl = BUILTIN_AUTH_SERVER_URL;

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

        while (true) {
            try {
                return checkMandatoryUpdate();
            } catch (MandatoryUpdateException e) {
                LOG.warning("The mandatory FreeCore launcher update could not be applied", e);
                showMandatoryUpdateFailure(e.getMessage());
                return false;
            } catch (Exception e) {
                LOG.warning("Failed to check for a mandatory FreeCore launcher update", e);
                if (!showUpdateCheckRetry()) {
                    return false;
                }
            }
        }
    }

    /// Returns the authentication endpoint loaded from the repository configuration.
    public static String getDefaultAuthServerUrl() {
        return defaultAuthServerUrl;
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

    /// Downloads and validates the repository configuration, retaining built-in defaults on failure.
    private static void refreshRemoteConfiguration() {
        remoteConfigurationLoaded = false;
        try {
            JsonObject root = JsonUtils.fromNonNullJson(
                    HttpRequest.GET(REMOTE_CONFIG_URL + "?timestamp=" + System.currentTimeMillis())
                            .header("Cache-Control", "no-cache")
                            .retry(2)
                            .getString(),
                    JsonObject.class);
            String configuredUrl = JsonUtils.getString(root, "defaultAuthServerUrl");
            if (configuredUrl == null || configuredUrl.isBlank()) {
                throw new JsonParseException("defaultAuthServerUrl is missing");
            }

            defaultAuthServerUrl = normalizeAuthServerUrl(configuredUrl);
            remoteConfigurationLoaded = true;
            updateRemoteConfigurationState(defaultAuthServerUrl);
            LOG.info("Loaded FreeCore remote configuration: defaultAuthServerUrl=" + defaultAuthServerUrl);
        } catch (Exception e) {
            previousAuthServerUrl = null;
            authServerChanged = false;
            try {
                defaultAuthServerUrl = readAppliedAuthenticationServerUrl();
                LOG.warning("Failed to load FreeCore remote configuration; using the last applied URL", e);
            } catch (Exception stateError) {
                defaultAuthServerUrl = BUILTIN_AUTH_SERVER_URL;
                stateError.addSuppressed(e);
                LOG.warning("Failed to load FreeCore remote configuration and local state; "
                        + "using the built-in URL", stateError);
            }
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

    /// Atomically persists the repository authentication URL applied to local account storage.
    private static void writeRemoteConfigurationState(String configuredUrl) throws IOException {
        Files.createDirectories(REMOTE_CONFIG_STATE_FILE.getParent());
        JsonObject state = new JsonObject();
        state.addProperty("defaultAuthServerUrl", configuredUrl);
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

        if (release.executableUrl() == null || release.checksumUrl() == null) {
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

    /// Fetches and parses the newest GitHub release and its mandatory update assets.
    private static Release fetchLatestRelease() throws IOException {
        JsonObject releaseObject = JsonUtils.fromNonNullJson(
                HttpRequest.GET(LATEST_RELEASE_URL)
                        .accept("application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .retry(2)
                        .getString(),
                JsonObject.class);

        String tagName = JsonUtils.getString(releaseObject, "tag_name");
        if (tagName == null || tagName.isBlank()) {
            throw new JsonParseException("Latest GitHub release does not contain tag_name");
        }

        JsonArray assets = releaseObject.getAsJsonArray("assets");
        if (assets == null) {
            throw new JsonParseException("Latest GitHub release does not contain assets");
        }

        return new Release(
                normalizeVersion(tagName),
                findAssetUrl(assets, WINDOWS_ASSET_NAME),
                findAssetUrl(assets, WINDOWS_CHECKSUM_ASSET_NAME));
    }

    /// Finds the download URL of a named GitHub release asset.
    private static @Nullable String findAssetUrl(JsonArray assets, String assetName) {
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

    /// Downloads, verifies, and hands the new executable to an external replacement script.
    private static void stageWindowsUpdate(
            Path currentExecutable,
            Release release,
            UpdateProgressDialog progressDialog) throws IOException {
        Path updateDirectory = Metadata.HMCL_LOCAL_HOME.resolve("update");
        Files.createDirectories(updateDirectory);
        Path downloadedExecutable = updateDirectory.resolve(WINDOWS_ASSET_NAME + ".download");
        Path updaterScript = updateDirectory.resolve("apply-freecore-update.cmd");

        progressDialog.setStatus("正在下载 FreeCore " + release.version() + "…");
        downloadFile(release.executableUrl(), downloadedExecutable);
        progressDialog.setStatus("正在校验更新文件…");
        String expectedChecksum = readExpectedChecksum(release.checksumUrl());
        String actualChecksum = DigestUtils.digestToString("SHA-256", downloadedExecutable);
        if (!actualChecksum.equalsIgnoreCase(expectedChecksum)) {
            Files.deleteIfExists(downloadedExecutable);
            throw new IOException("Downloaded FreeCore launcher checksum mismatch");
        }

        String script = buildWindowsUpdaterScript(
                ProcessHandle.current().pid(),
                downloadedExecutable,
                currentExecutable,
                release.version());
        Files.writeString(
                updaterScript,
                script,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);

        progressDialog.setStatus("校验完成，正在重启并安装更新…");
        new ProcessBuilder("cmd.exe", "/d", "/s", "/c", "\"" + updaterScript + "\"")
                .directory(currentExecutable.getParent().toFile())
                .start();
        LOG.info("FreeCore updater started for version " + release.version());
    }

    /// Downloads an update asset without retaining it in memory.
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
        } finally {
            connection.disconnect();
        }
        Files.move(temporaryOutput, output, StandardCopyOption.REPLACE_EXISTING);
    }

    /// Reads a conventional hexadecimal SHA-256 sidecar.
    private static String readExpectedChecksum(String url) throws IOException {
        String content = HttpRequest.GET(url).retry(2).getString().trim();
        String checksum = content.split("\\s+", 2)[0];
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

    /// Creates a command script that waits for exit, replaces the EXE, and restarts it.
    private static String buildWindowsUpdaterScript(
            long processId,
            Path downloadedExecutable,
            Path currentExecutable,
            String version) {
        String source = escapeBatchPath(downloadedExecutable);
        String target = escapeBatchPath(currentExecutable);
        String workingDirectory = escapeBatchPath(currentExecutable.getParent());
        return "@echo off\r\n"
                + "setlocal\r\n"
                + ":wait_for_launcher\r\n"
                + "tasklist /fi \"PID eq " + processId + "\" 2>nul | find \"" + processId + "\" >nul\r\n"
                + "if not errorlevel 1 (\r\n"
                + "  ping 127.0.0.1 -n 2 >nul\r\n"
                + "  goto wait_for_launcher\r\n"
                + ")\r\n"
                + "copy /y \"" + source + "\" \"" + target + "\" >nul\r\n"
                + "if errorlevel 1 goto update_failed\r\n"
                + "del /q \"" + source + "\" >nul 2>&1\r\n"
                + "cd /d \"" + workingDirectory + "\"\r\n"
                + "set \"" + UPDATED_VERSION_ENVIRONMENT + "=" + version + "\"\r\n"
                + "start \"\" \"" + target + "\"\r\n"
                + "del /q \"%~f0\" >nul 2>&1\r\n"
                + "exit /b 0\r\n"
                + ":update_failed\r\n"
                + "start \"\" /wait powershell.exe -NoProfile -Command "
                + "\"Add-Type -AssemblyName PresentationFramework; "
                + "[System.Windows.MessageBox]::Show('FreeCore 更新失败，请检查文件权限后重新启动。','FreeCore 更新失败')\"\r\n"
                + "exit /b 1\r\n";
    }

    /// Normalizes an authentication endpoint and requires HTTPS.
    private static String normalizeAuthServerUrl(String url) {
        String normalized = url.trim();
        if (!normalized.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IllegalArgumentException("FreeCore authentication endpoint must use HTTPS");
        }
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    /// Removes a conventional v prefix before version comparison.
    private static String normalizeVersion(String version) {
        String normalized = Objects.requireNonNull(version).trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            return normalized.substring(1);
        }
        return normalized;
    }

    /// Escapes percent characters interpreted by command scripts.
    private static String escapeBatchPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("%", "%%");
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
                "无法连接 GitHub 检查 FreeCore 强制更新。\n请检查网络或代理设置后重试。",
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
    /// @param executableUrl Windows executable download URL
    /// @param checksumUrl SHA-256 sidecar download URL
    private record Release(
            String version,
            @Nullable String executableUrl,
            @Nullable String checksumUrl) {
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

        /// Closes the progress window after a failed update attempt.
        private void close() {
            SwingUtilities.invokeLater(dialog::dispose);
        }
    }
}
