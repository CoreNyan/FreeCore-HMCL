/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2025 huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.util.platform.windows;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import org.jackhuang.hmcl.util.platform.NativeUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Provides mappings for the user interface functions used by HMCL.
///
/// @author Glavo
@NotNullByDefault
public interface User32 extends StdCallLibrary {

    /// Callback interface for EnumWindows.
    interface WNDENUMPROC extends StdCallCallback {
        boolean callback(Pointer hWnd, Pointer lParam);
    }

    /// The loaded User32 library, or `null` when JNA is unavailable or the current platform is not Windows.
    @Nullable User32 INSTANCE = NativeUtils.USE_JNA && com.sun.jna.Platform.isWindows()
            ? NativeUtils.load("user32", User32.class)
            : null;

    /// Retrieves a device context for a window or for the entire screen.
    @Nullable Pointer GetDC(@Nullable Pointer hWnd);

    /// Releases a device context retrieved by {@link #GetDC(Pointer)}.
    int ReleaseDC(@Nullable Pointer hWnd, Pointer hDC);

    /// Retrieves a handle to the top-level window whose class name and window name match the specified strings.
    @Nullable Pointer FindWindowW(@Nullable WString lpClassName, @Nullable WString lpWindowName);

    /// Changes the parent window of the specified child window.
    @Nullable Pointer SetParent(Pointer hWndChild, @Nullable Pointer hWndNewParent);

    /// Changes the position and dimensions of the specified window.
    boolean MoveWindow(Pointer hWnd, int X, int Y, int nWidth, int nHeight, boolean bRepaint);

    /// Changes an attribute of the specified window.
    int SetWindowLongW(Pointer hWnd, int nIndex, int dwNewLong);

    /// Retrieves information about the specified window.
    int GetWindowLongW(Pointer hWnd, int nIndex);

    /// Sets the specified window's show state.
    boolean ShowWindow(Pointer hWnd, int nCmdShow);

    /// Brings the thread that created the specified window into the foreground and activates the window.
    boolean SetForegroundWindow(Pointer hWnd);

    /// Enumerates all top-level windows on the screen.
    boolean EnumWindows(WNDENUMPROC lpEnumFunc, Pointer lParam);

    /// Retrieves the identifier of the thread and process that created the specified window.
    int GetWindowThreadProcessId(Pointer hWnd, IntByReference lpdwProcessId);

    /// Checks if a window is visible.
    boolean IsWindowVisible(Pointer hWnd);
}