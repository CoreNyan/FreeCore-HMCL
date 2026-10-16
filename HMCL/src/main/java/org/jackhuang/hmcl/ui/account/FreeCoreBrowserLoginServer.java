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
package org.jackhuang.hmcl.ui.account;

import javafx.application.Platform;
import javafx.stage.Stage;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.AccountID;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorAccount;
import org.jackhuang.hmcl.auth.yggdrasil.GameProfile;
import org.jackhuang.hmcl.auth.yggdrasil.YggdrasilSession;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.upgrade.FreeCoreBootstrap;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.Locale;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Authorizes in the user's default browser without intercepting passwords or debugging their browser.
@NotNullByDefault
public final class FreeCoreBrowserLoginServer {
    /// Current request, guarded by this class's monitor.
    private static @Nullable AtomicBoolean active;

    /// Prevents construction of the login coordinator.
    private FreeCoreBrowserLoginServer() {}

    /// Opens the system browser; a PKCE-bound device request delivers the approved account asynchronously.
    public static synchronized void startLoginFlow(@Nullable Consumer<Account> onAccountAdded) {
        if (active != null) {
            Platform.runLater(() -> Controllers.showToast("登录窗口已打开，请回到现有窗口继续授权"));
            return;
        }
        AtomicBoolean running = new AtomicBoolean(true);
        active = running;
        Schedulers.io().execute(() -> {
            try {
                FreeCoreBrowserAuthorization authorization = FreeCoreBrowserAuthorization.configured();
                URI url = authorization.start();
                if (!running.get()) return;
                FXUtils.openLink(url.toString());
                Platform.runLater(() -> Controllers.showToast("请在默认浏览器登录并确认授权，完成后会自动添加账户"));
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
                int expiredPolls = 0;
                while (running.get() && System.nanoTime() < deadline) {
                    @Nullable FreeCoreBrowserAuthorization.Credentials credentials;
                    try {
                        credentials = authorization.poll();
                        expiredPolls = 0;
                    } catch (FreeCoreBrowserAuthorization.AuthorizationExpiredException expired) {
                        if (++expiredPolls >= 3) throw expired;
                        TimeUnit.MILLISECONDS.sleep(750);
                        continue;
                    }
                    if (credentials != null) {
                        register(credentials, running, onAccountAdded);
                        return;
                    }
                    TimeUnit.SECONDS.sleep(2);
                }
                if (running.get()) throw new IOException("浏览器登录已超时，请重新登录");
            } catch (UnsupportedOperationException e) {
                // The currently deployed Blessing Skin has no launcher handoff API yet.
                // Keep the user-facing flow useful while avoiding fake account creation.
                if (running.get()) Platform.runLater(() -> {
                    FXUtils.openLink(FreeCoreBootstrap.getLoginUrl());
                    Controllers.showToast("当前皮肤站尚未启用启动器授权回调；已在默认浏览器打开登录页");
                });
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                if (e instanceof FreeCoreBrowserAuthorization.AuthorizationExpiredException expired) {
                    LOG.warning("FreeCore browser authorization failed: code=" + expired.codeFingerprint()
                            + ", server=" + expired.serverReason(), e);
                } else {
                    LOG.warning("FreeCore browser authorization failed", e);
                }
                if (running.get()) Platform.runLater(() -> Controllers.showToast(e.getMessage()));
            } finally {
                synchronized (FreeCoreBrowserLoginServer.class) {
                    if (active == running) active = null;
                }
            }
        });
    }

    /// Opens browser authorization without an account completion callback.
    public static void startLoginFlow() { startLoginFlow(null); }

    /// Validates a real server-issued session before updating launcher UI state.
    private static void register(FreeCoreBrowserAuthorization.Credentials credentials, AtomicBoolean running,
            @Nullable Consumer<Account> callback) throws Exception {
        YggdrasilSession session = credentials.session();
        GameProfile profile = credentials.profile();
        var service = Accounts.FREECORE_AUTH_SERVER.getYggdrasilService();
        @Nullable GameProfile selected = session.getSelectedProfile();
        if (selected == null || !selected.getId().equals(profile.getId())) {
            session = service.refresh(session.getAccessToken(), session.getClientToken(), profile);
        }
        if (!service.validate(session.getAccessToken(), session.getClientToken()))
            throw new IOException("服务器返回的游戏会话无效，请重新授权");
        YggdrasilSession verified = session;
        FutureTask<AuthlibInjectorAccount> prepare = new FutureTask<>(() -> {
            AccountID id = AccountID.generate();
            for (Account existing : Accounts.getAccounts()) {
                if (existing instanceof AuthlibInjectorAccount account && Accounts.isFreeCoreServer(account.getServer())
                        && (profile.getId().equals(account.getProfileID())
                        || credentials.login().toLowerCase(Locale.ROOT).equals(account.getLoginName().toLowerCase(Locale.ROOT)))) {
                    id = existing.getAccountID();
                    break;
                }
            }
            return new AuthlibInjectorAccount(id, Accounts.FREECORE_AUTH_SERVER,
                    Accounts.FACTORY_FREECORE.getDownloader(), credentials.login(), verified);
        });
        Platform.runLater(prepare);
        AuthlibInjectorAccount account = prepare.get();
        if (!running.get()) return;
        FutureTask<Boolean> commit = new FutureTask<>(() -> {
            if (!running.get()) return false;
            int index = -1;
            for (int i = 0; i < Accounts.getAccounts().size(); i++) {
                if (Accounts.getAccounts().get(i).getAccountID().equals(account.getAccountID())) { index = i; break; }
            }
            if (index >= 0) Accounts.getAccounts().set(index, account);
            else Accounts.getAccounts().add(account);
            Accounts.setSelectedAccount(account);
            @Nullable Stage stage = Controllers.getStage();
            if (stage != null) { stage.setIconified(false); stage.show(); stage.toFront(); stage.requestFocus(); }
            Controllers.showToast("登录成功：" + profile.getName());
            if (callback != null) callback.accept(account);
            return true;
        });
        Platform.runLater(commit);
        // Only the I/O worker waits; keep cancellation active until the final FX commit.
        commit.get();
    }
}
