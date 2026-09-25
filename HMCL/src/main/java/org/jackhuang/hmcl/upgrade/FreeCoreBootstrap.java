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

import javax.swing.JOptionPane;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

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

    /// Windows executable asset required in every launcher release.
    private static final String WINDOWS_ASSET_NAME = "FreeCore-Launcher.exe";

    /// SHA-256 sidecar required beside the Windows executable asset.
    private static final String WINDOWS_CHECKSUM_ASSET_NAME = WINDOWS_ASSET_NAME + ".sha256";

    /// Environment marker used to avoid rechecking the same version directly after replacement.
    private static final String UPDATED_VERSION_ENVIRONMENT = "FREECORE_UPDATED_TO";

    /// Current configured authentication endpoint.
    private static volatile String defaultAuthServerUrl = BUILTIN_AUTH_SERVER_URL;

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
        } catch (Exception e) {
            LOG.warning("Failed to check for a FreeCore launcher update; continuing with the installed version", e);
            return true;
        }
    }

    /// Returns the authentication endpoint loaded from the repository configuration.
    public static String getDefaultAuthServerUrl() {
        return defaultAuthServerUrl;
    }

    /// Downloads and validates the repository configuration, retaining built-in defaults on failure.
    private static void refreshRemoteConfiguration() {
        try {
            JsonObject root = JsonUtils.fromNonNullJson(
                    HttpRequest.GET(REMOTE_CONFIG_URL)
                            .header("Cache-Control", "no-cache")
                            .retry(2)
                            .getString(),
                    JsonObject.class);
            String configuredUrl = JsonUtils.getString(root, "defaultAuthServerUrl");
            if (configuredUrl == null || configuredUrl.isBlank()) {
                throw new JsonParseException("defaultAuthServerUrl is missing");
            }

            defaultAuthServerUrl = normalizeAuthServerUrl(configuredUrl);
            LOG.info("Loaded FreeCore remote configuration: defaultAuthServerUrl=" + defaultAuthServerUrl);
        } catch (Exception e) {
            defaultAuthServerUrl = BUILTIN_AUTH_SERVER_URL;
            LOG.warning("Failed to load FreeCore remote configuration; using built-in defaults", e);
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
        try {
            stageWindowsUpdate(currentExecutable, release);
        } catch (IOException e) {
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
    private static void stageWindowsUpdate(Path currentExecutable, Release release) throws IOException {
        Path updateDirectory = Metadata.HMCL_LOCAL_HOME.resolve("update");
        Files.createDirectories(updateDirectory);
        Path downloadedExecutable = updateDirectory.resolve(WINDOWS_ASSET_NAME + ".download");
        Path updaterScript = updateDirectory.resolve("apply-freecore-update.cmd");

        downloadFile(release.executableUrl(), downloadedExecutable);
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

        new ProcessBuilder("cmd.exe", "/d", "/c", updaterScript.toString())
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
}
