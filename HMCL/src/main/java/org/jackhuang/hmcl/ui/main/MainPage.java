/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.ui.main;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXPopup;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.RotateTransition;
import javafx.animation.Timeline;
import javafx.beans.property.*;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.download.DefaultDependencyManager;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.download.VersionList;
import org.jackhuang.hmcl.game.*;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.theme.Themes;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.animation.AnimationUtils;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.instances.GameListPopupMenu;
import org.jackhuang.hmcl.ui.instances.Instances;
import org.jackhuang.hmcl.upgrade.RemoteVersion;
import org.jackhuang.hmcl.upgrade.UpdateChecker;
import org.jackhuang.hmcl.upgrade.UpdateHandler;
import org.jackhuang.hmcl.util.*;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jackhuang.hmcl.util.javafx.BindingMapping;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.util.platform.Platform;
import org.jackhuang.hmcl.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.awt.Desktop;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.download.RemoteVersion.Type.RELEASE;
import static org.jackhuang.hmcl.setting.SettingsManager.state;
import static org.jackhuang.hmcl.ui.FXUtils.SINE;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Displays the launcher home controls for the currently selected game repository.
public final class MainPage extends StackPane implements DecoratorPage {
    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>();

    private final ObjectProperty<@Nullable HMCLGameInstance> currentGame = new SimpleObjectProperty<>(this, "currentGame");
    private final BooleanProperty showUpdate = new SimpleBooleanProperty(this, "showUpdate");
    private final BooleanProperty showUpdateDialog = new SimpleBooleanProperty(this, "showUpdateDialog");
    private final ObjectProperty<RemoteVersion> latestVersion = new SimpleObjectProperty<>(this, "latestVersion");
    /// Mutable storage for visible instances from the selected repository's current snapshot.
    private final ObservableList<HMCLGameInstance> mutableInstances = FXCollections.observableArrayList();

    /// Read-only observable view of [#mutableInstances].
    private final @UnmodifiableView ObservableList<HMCLGameInstance> instances =
            FXCollections.unmodifiableObservableList(mutableInstances);

    /// Current snapshot of the repository selected by [GameDirectoryManager].
    private final ObservableValue<HMCLGameRepositorySnapshot> selectedRepositorySnapshot =
            BindingMapping.of(GameDirectoryManager.selectedRepositoryProperty())
                    .flatMap(HMCLGameRepository::snapshotProperty);

    private final StackPane updatePane;
    private final JFXButton menuButton;

    private RemoteVersion lastShownVersion;

    {
        HBox titleNode = new HBox(8);
        titleNode.setPadding(new Insets(0, 0, 0, 2));
        titleNode.setAlignment(Pos.CENTER_LEFT);

        ImageView titleIcon = new ImageView(FXUtils.newBuiltinImage("/assets/img/freecore-brand-transparent.png"));
        titleIcon.setFitWidth(26);
        titleIcon.setFitHeight(26);
        titleIcon.setPreserveRatio(true);
        Label titleLabel = new Label(Metadata.FULL_TITLE);
        if (I18n.isUpsideDown()) {
            titleIcon.setRotate(180);
            titleLabel.setRotate(180);
        }
        titleLabel.getStyleClass().add("jfx-decorator-title");
        titleLabel.textFillProperty().bind(Themes.titleFillProperty());
        titleNode.getChildren().setAll(titleIcon, titleLabel);

        state.setValue(new State(null, titleNode, false, false, true));

        setPadding(new Insets(20));

        // Keep the home page recognizable as the FreeCore client even before an instance is selected.
        VBox brandPane = new VBox(10);
        brandPane.setAlignment(Pos.CENTER);
        brandPane.setMouseTransparent(true);
        brandPane.getStyleClass().add("freecore-brand-pane");

        ImageView brandIcon = new ImageView(FXUtils.newBuiltinImage("/assets/img/freecore-brand-transparent.png"));
        brandIcon.setFitWidth(128);
        brandIcon.setFitHeight(128);
        brandIcon.setPreserveRatio(true);
        brandIcon.setMouseTransparent(true);
        brandIcon.getStyleClass().add("freecore-brand-icon");

        Label brandName = new Label("FreeCore");
        brandName.setMouseTransparent(true);
        brandName.getStyleClass().add("freecore-brand-title");
        Label brandTagline = new Label("TRACE THE FREE  ·  ANCHOR THE CORE");
        brandTagline.setMouseTransparent(true);
        brandTagline.getStyleClass().add("freecore-brand-tagline");

        Button docsButton = createFreeCoreLinkButton("官方文档", Metadata.DOCS_URL);
        Button accountButton = createFreeCoreLinkButton("个人中心", "https://account.freecore.cc");
        Button groupButton = createFreeCoreLinkButton("QQ群", "https://qm.qq.com/q/fwCibn5mjC");
        Button websiteButton = createFreeCoreLinkButton("官网", "https://freecore.cc");
        HBox brandLinks = new HBox(8, docsButton, accountButton, groupButton, websiteButton);
        brandLinks.setAlignment(Pos.CENTER);
        // Only the four controls should be hit-test targets. This prevents the centered layout
        // container from swallowing mouse clicks while retaining a generous click target.
        brandLinks.setPickOnBounds(false);
        brandLinks.setMouseTransparent(false);
        brandPane.getChildren().setAll(brandIcon, brandName, brandTagline);

        // Keep the brand and links in one vertical flow. Previously they were two independently
        // centered StackPane children, so the links could drift over the title on narrow windows.
        VBox heroPane = new VBox(24, brandPane, brandLinks);
        heroPane.setAlignment(Pos.CENTER);
        heroPane.setPickOnBounds(false);
        StackPane.setAlignment(heroPane, Pos.CENTER);
        StackPane.setMargin(heroPane, new Insets(-18, 0, 42, 0));
        getChildren().add(heroPane);

        if (AnimationUtils.isAnimationEnabled()) {
            brandPane.setOpacity(0);
            brandPane.setTranslateY(16);
            new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(brandPane.opacityProperty(), 0),
                            new KeyValue(brandPane.translateYProperty(), 16)),
                    new KeyFrame(Duration.millis(750),
                            new KeyValue(brandPane.opacityProperty(), 1, SINE),
                            new KeyValue(brandPane.translateYProperty(), 0, SINE))
            ).play();
        }

        updatePane = new StackPane();
        updatePane.setVisible(false);
        updatePane.getStyleClass().add("bubble");
        FXUtils.setLimitWidth(updatePane, 230);
        FXUtils.setLimitHeight(updatePane, 55);
        StackPane.setAlignment(updatePane, Pos.TOP_RIGHT);
        FXUtils.onClicked(updatePane, this::onUpgrade);
        updatePane.setCursor(Cursor.HAND);
        FXUtils.onChange(showUpdateProperty(), this::doAnimation);
        FXUtils.onChange(showUpdateDialogProperty(), this::showUpdateDialog);

        {
            HBox hBox = new HBox();
            hBox.setSpacing(12);
            hBox.setAlignment(Pos.CENTER_LEFT);
            StackPane.setAlignment(hBox, Pos.CENTER_LEFT);
            StackPane.setMargin(hBox, new Insets(9, 12, 9, 16));
            {
                TwoLineListItem prompt = new TwoLineListItem();
                prompt.setSubtitle(i18n("update.bubble.subtitle"));
                prompt.setPickOnBounds(false);
                prompt.titleProperty().bind(BindingMapping.of(latestVersionProperty()).map(latestVersion ->
                        latestVersion == null ? "" : i18n("update.bubble.title", latestVersion.version())));

                hBox.getChildren().setAll(SVG.UPDATE.createIcon(20), prompt);
            }

            JFXButton closeUpdateButton = new JFXButton();
            closeUpdateButton.setGraphic(SVG.CLOSE.createIcon(10));
            StackPane.setAlignment(closeUpdateButton, Pos.TOP_RIGHT);
            closeUpdateButton.getStyleClass().add("toggle-icon-tiny");
            StackPane.setMargin(closeUpdateButton, new Insets(5));
            closeUpdateButton.setOnAction(e -> closeUpdateBubble());

            updatePane.getChildren().setAll(hBox, closeUpdateButton);
        }

        HBox launchPane = new HBox();
        launchPane.getStyleClass().add("launch-pane");
        FXUtils.onChangeAndOperate(selectedRepositorySnapshot, ignored -> mutableInstances.setAll(GameDirectoryManager.getSelectedRepository().getDisplayInstances().toList()));
        FXUtils.onScroll(launchPane, instances, list -> {
            @Nullable HMCLGameInstance currentGame = getCurrentGame();
            @Nullable GameInstanceID currentId = currentGame != null ? currentGame.getId() : null;
            return Lang.indexWhere(list, instance -> instance.getId().equals(currentId));
        }, instance -> instance.getRepository().setSelectedInstance(instance));

        StackPane.setAlignment(launchPane, Pos.BOTTOM_RIGHT);
        {
            JFXButton launchButton = new JFXButton();
            launchButton.getStyleClass().add("launch-button");
            launchButton.setDefaultButton(true);
            {
                VBox graphic = new VBox();
                graphic.setAlignment(Pos.CENTER);
                Label launchLabel = new Label();
                launchLabel.setStyle("-fx-font-size: 16px;");
                Label currentLabel = new Label();
                currentLabel.setStyle("-fx-font-size: 12px;");

                FXUtils.onChangeAndOperate(currentGameProperty(), new Consumer<>() {
                    private Tooltip tooltip;

                    @Override
                    public void accept(@Nullable HMCLGameInstance currentGame) {
                        if (currentGame == null) {
                            launchLabel.setText(i18n("instance.launch.empty"));
                            currentLabel.setText(null);
                            graphic.getChildren().setAll(launchLabel);
                            FXUtils.setOnActionWithCooldown(launchButton, MainPage.this::launchNoGame);
                            if (tooltip == null)
                                tooltip = new Tooltip(i18n("instance.launch.empty.tooltip"));
                            FXUtils.installFastTooltip(launchButton, tooltip);
                        } else {
                            launchLabel.setText(i18n("instance.launch"));
                            currentLabel.setText(currentGame.getId().toString());
                            graphic.getChildren().setAll(launchLabel, currentLabel);
                            FXUtils.setOnActionWithCooldown(launchButton, MainPage.this::launch);
                            if (tooltip != null)
                                Tooltip.uninstall(launchButton, tooltip);
                        }
                    }
                });

                launchButton.setGraphic(graphic);
            }

            menuButton = new JFXButton();
            menuButton.getStyleClass().add("menu-button");
            menuButton.setOnAction(e -> {
                if (GameListPopupMenu.hideShowing(menuButton)) {
                    return;
                }

                JFXPopup popup = GameListPopupMenu.showAndGetPopup(
                        menuButton,
                        JFXPopup.PopupVPosition.BOTTOM,
                        JFXPopup.PopupHPosition.RIGHT,
                        0,
                        -menuButton.getHeight(),
                        instances
                );

                Node graphic = menuButton.getGraphic();
                if (graphic != null) {
                    if (AnimationUtils.isAnimationEnabled()) {
                        Duration duration = Duration.millis(200);
                        RotateTransition rotateOpen = new RotateTransition(duration, graphic);
                        rotateOpen.setToAngle(-180);
                        FXUtils.playAnimation(graphic, "arrow-rotation", rotateOpen);

                        popup.setOnHidden(windowEvent -> {
                            RotateTransition rotateClose = new RotateTransition(duration, graphic);
                            rotateClose.setToAngle(0);
                            FXUtils.playAnimation(graphic, "arrow-rotation", rotateClose);
                        });
                    } else {
                        graphic.setRotate(-180);
                        popup.setOnHidden(windowEvent -> graphic.setRotate(0));
                    }
                }
            });
            FXUtils.installFastTooltip(menuButton, i18n("instance.switch"));
            menuButton.setGraphic(SVG.ARROW_DROP_UP.createIcon(30));

            EventHandler<MouseEvent> secondaryClickHandle = event -> {
                if (event.getButton() == MouseButton.SECONDARY && event.getClickCount() == 1) {
                    menuButton.fire();
                    event.consume();
                }
            };
            launchButton.addEventHandler(MouseEvent.MOUSE_CLICKED, secondaryClickHandle);
            menuButton.addEventHandler(MouseEvent.MOUSE_CLICKED, secondaryClickHandle);

            launchPane.getChildren().setAll(launchButton, menuButton);
        }

        getChildren().addAll(updatePane, launchPane);

    }

    /// Opens a FreeCore link from a button action using the desktop browser integration.
    ///
    /// @param link the absolute HTTP(S) link to open
    private static void openFreeCoreLink(String link) {
        Lang.thread(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI.create(link));
                } else {
                    FXUtils.openLink(link);
                }
            } catch (Throwable exception) {
                FXUtils.openLink(link);
            }
        }, "Open FreeCore Link", true);
    }

    /// Creates a large, focusable home-page link button.
    ///
    /// @param text the visible button label
    /// @param link the absolute HTTP(S) destination
    /// @return a configured link button
    private static Button createFreeCoreLinkButton(String text, String link) {
        Button button = new Button(text);
        button.getStyleClass().add("freecore-link-button");
        button.setMinSize(120, 56);
        button.setPrefSize(120, 56);
        button.setMaxSize(120, 56);
        button.setPickOnBounds(true);
        button.setFocusTraversable(true);
        button.setOnAction(event -> openFreeCoreLink(link));
        return button;
    }

    private void showUpdateDialog(boolean show) {
        if (show && getLatestVersion() != null && !Objects.equals(getLatestVersion(), lastShownVersion)
                && !Objects.equals(state().getPromptedVersion(), getLatestVersion().version())
        ) {
            lastShownVersion = getLatestVersion();
            Controllers.dialogLater(new MessageDialogPane.Builder("", i18n("update.bubble.title", getLatestVersion().version()), MessageDialogPane.MessageType.INFO)
                    .addAction(i18n("button.view"), () -> {
                        state().setPromptedVersion(getLatestVersion().version());
                        onUpgrade();
                    })
                    .addCancel(null)
                    .build());
        }
    }

    private void doAnimation(boolean show) {
        if (AnimationUtils.isAnimationEnabled()) {
            Duration duration = Duration.millis(320);
            Timeline nowAnimation = new Timeline();
            nowAnimation.getKeyFrames().addAll(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(updatePane.translateXProperty(), show ? 260 : 0, SINE)),
                    new KeyFrame(duration,
                            new KeyValue(updatePane.translateXProperty(), show ? 0 : 260, SINE)));
            if (show) nowAnimation.getKeyFrames().add(
                    new KeyFrame(Duration.ZERO, e -> updatePane.setVisible(true)));
            else nowAnimation.getKeyFrames().add(
                    new KeyFrame(duration, e -> updatePane.setVisible(false)));
            nowAnimation.play();
        } else {
            updatePane.setVisible(show);
        }
    }

    private void launch() {
        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        Instances.launch(repository.getSelectedInstance());
    }

    private void launchNoGame() {
        DownloadProvider downloadProvider = DownloadProviders.getDownloadProvider();
        VersionList<?> versionList = downloadProvider.getVersionList(GameComponentType.GAME);

        Holder<GameInstanceID> instanceHolder = new Holder<>();
        Task<?> task = versionList.refreshAsync("")
                .thenSupplyAsync(() -> versionList.getVersions("").stream()
                        .filter(it -> it.getVersionType() == RELEASE)
                        .filter(it -> NativePatcher.checkSupportedStatus(GameVersionNumber.asGameVersion(it.getGameVersion()), Platform.SYSTEM_PLATFORM, OperatingSystem.SYSTEM_VERSION) != NativePatcher.SupportStatus.UNSUPPORTED)
                        .sorted()
                        .findFirst()
                        .orElseThrow(() -> new IOException("No versions found")))
                .thenComposeAsync(version -> {
                    HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
                    DefaultDependencyManager dependency = repository.getDependency();

                    String gameVersion = version.getGameVersion();
                    GameInstanceID instanceId = new GameInstanceID(gameVersion);

                    instanceHolder.value = instanceId;

                    return dependency.newGameBuilder()
                            .id(instanceId)
                            .component(GameComponentType.GAME, gameVersion)
                            .buildAsync();
                })
                .whenComplete(any -> GameDirectoryManager.getSelectedRepository().refresh())
                .whenComplete(Schedulers.javafx(), (result, exception) -> {
                    if (exception == null) {
                        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
                        repository.setSelectedInstance(repository.getInstance(instanceHolder.value));
                        launch();
                    } else if (!(exception instanceof CancellationException)) {
                        LOG.warning("Failed to install game", exception);
                        Controllers.dialog(StringUtils.getStackTrace(exception),
                                i18n("install.failed"),
                                MessageDialogPane.MessageType.WARNING);
                    }
                });
        Controllers.taskDialog(task, i18n("instance.launch.empty.installing"), TaskCancellationAction.NORMAL);
    }

    private void onUpgrade() {
        RemoteVersion target = UpdateChecker.getLatestVersion();
        if (target == null) {
            return;
        }
        UpdateHandler.updateFrom(target);
    }

    private void closeUpdateBubble() {
        showUpdate.unbind();
        showUpdate.set(false);
    }

    @Override
    public ReadOnlyObjectWrapper<State> stateProperty() {
        return state;
    }

    /// Returns the instance shown by the launch controls.
    ///
    /// @return the current instance, or `null` when no instance is selected
    public @Nullable HMCLGameInstance getCurrentGame() {
        return currentGame.get();
    }

    /// Returns the property for the instance shown by the launch controls.
    ///
    /// @return the current-instance property
    public ObjectProperty<@Nullable HMCLGameInstance> currentGameProperty() {
        return currentGame;
    }

    /// Sets the instance shown by the launch controls.
    ///
    /// @param currentGame the instance to show, or `null` to show the empty state
    public void setCurrentGame(@Nullable HMCLGameInstance currentGame) {
        this.currentGame.set(currentGame);
    }

    /// Returns the observable instances displayed by launch-selection controls.
    ///
    /// The list is updated from the selected repository's published snapshot and contains no hidden
    /// instances. The returned view cannot be mutated.
    ///
    /// @return the observable launch-menu instances
    public @UnmodifiableView ObservableList<HMCLGameInstance> getInstances() {
        return instances;
    }

    public boolean isShowUpdate() {
        return showUpdate.get();
    }

    public BooleanProperty showUpdateProperty() {
        return showUpdate;
    }

    public void setShowUpdate(boolean showUpdate) {
        this.showUpdate.set(showUpdate);
    }

    public boolean isShowUpdateDialog() {
        return showUpdateDialog.get();
    }

    public BooleanProperty showUpdateDialogProperty() {
        return showUpdateDialog;
    }

    public void setShowUpdateDialog(boolean showUpdateDialog) {
        this.showUpdateDialog.set(showUpdateDialog);
    }

    public RemoteVersion getLatestVersion() {
        return latestVersion.get();
    }

    public ObjectProperty<RemoteVersion> latestVersionProperty() {
        return latestVersion;
    }

    public void setLatestVersion(RemoteVersion latestVersion) {
        this.latestVersion.set(latestVersion);
    }

}
