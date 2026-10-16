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

import javafx.beans.binding.Bindings;
import javafx.beans.property.ListProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleListProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Skin;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.AdvancedListItem;
import org.jackhuang.hmcl.ui.construct.ClassTitle;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.util.javafx.MappedObservableList;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.javafx.ExtendedProperties.createSelectedItemPropertyFor;

/// Displays the launcher's account list and provides one-click system-browser FreeCore login.
@NotNullByDefault
public final class AccountListPage extends DecoratorAnimatedPage implements DecoratorPage {

    /// Live visual items mapped from configured accounts.
    private final ObservableList<AccountListItem> items;

    /// Read-only page state wrapper displayed in the window decorator.
    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("account.manage")));

    /// Bound list of configured user accounts.
    private final ListProperty<Account> accounts =
            new SimpleListProperty<>(this, "accounts", FXCollections.observableArrayList());

    /// Bound property representing the currently selected user account.
    private final ObjectProperty<Account> selectedAccount;

    /// Initializes the account management page.
    public AccountListPage() {
        items = MappedObservableList.create(accounts, AccountListItem::new);
        selectedAccount = createSelectedItemPropertyFor(items, Account.class);
    }

    /// Returns the selected account property.
    ///
    /// @return the selected account property
    public ObjectProperty<Account> selectedAccountProperty() {
        return selectedAccount;
    }

    /// Returns the accounts list property.
    ///
    /// @return the accounts property
    public ListProperty<Account> accountsProperty() {
        return accounts;
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new AccountListPageSkin(this);
    }

    /// Custom skin rendering the streamlined account center sidebar and account card list.
    private static class AccountListPageSkin extends DecoratorAnimatedPageSkin<AccountListPage> {

        /// Creates the streamlined account list skin.
        ///
        /// @param skinnable the enclosing account list page
        public AccountListPageSkin(AccountListPage skinnable) {
            super(skinnable);

            VBox boxMethods = new VBox();
            boxMethods.getStyleClass().add("advanced-list-box-content");
            FXUtils.setLimitWidth(boxMethods, 200);

            ClassTitle title = new ClassTitle(i18n("account.center").toUpperCase(Locale.ROOT));

            AdvancedListItem webLoginItem = new AdvancedListItem();
            webLoginItem.getStyleClass().add("freecore-login-btn");
            webLoginItem.getStyleClass().add("navigation-drawer-item");
            webLoginItem.setTitle(i18n("account.add"));
            webLoginItem.setSubtitle(Accounts.FREECORE_AUTH_SERVER.getDisplayHostUrl());
            webLoginItem.setLeftIcon(SVG.PUBLIC);
            webLoginItem.setOnAction(e -> {
                if (SettingsManager.isUserGameAccountsReadOnly()) {
                    confirmOverwriteUserAccounts(FreeCoreBrowserLoginServer::startLoginFlow);
                } else {
                    FreeCoreBrowserLoginServer.startLoginFlow();
                }
            });

            boxMethods.getChildren().setAll(title, webLoginItem);

            ScrollPane leftScrollPane = new ScrollPane(boxMethods);
            VBox.setVgrow(leftScrollPane, Priority.ALWAYS);
            setLeft(leftScrollPane);

            ScrollPane scrollPane = new ScrollPane();
            VBox list = new VBox();
            scrollPane.setFitToWidth(true);
            list.maxWidthProperty().bind(scrollPane.widthProperty());
            list.setSpacing(10);
            list.getStyleClass().add("card-list");

            Bindings.bindContent(list.getChildren(), skinnable.items);
            scrollPane.setContent(list);
            FXUtils.smoothScrolling(scrollPane);

            setCenter(scrollPane);
        }

        /// Confirms overwriting the user account files before continuing the account operation.
        private static void confirmOverwriteUserAccounts(Runnable action) {
            Controllers.confirmBackupAndOverwrite(i18n("account.storage.read_only"), () -> {
                SettingsManager.forceOverwriteUserGameAccounts();
                action.run();
            });
        }
    }
}
