package com.jellypudding.offlineclient.rig.core;

import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.system.windows.User32;

import java.nio.IntBuffer;

// Keeps the window of the test game out of the way of whoever uses the machine. It sits in the
// bottom right corner of the main screen beneath every other window and never asks for the keyboard.
public final class WindowPlace {

    // Room left for the frame of the window and the edge of the screen.
    private static final int MARGIN = 40;
    private static final long NO_WINDOW = 0;

    private WindowPlace() {
    }

    public static void tuckAway(long window) {
        if (window == NO_WINDOW) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_Rect screen = SDL_Rect.calloc(stack);
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            if (SDLVideo.SDL_GetDisplayUsableBounds(SDLVideo.SDL_GetPrimaryDisplay(), screen)
                && SDLVideo.SDL_GetWindowSize(window, width, height)) {
                SDLVideo.SDL_SetWindowPosition(window, screen.x() + screen.w() - width.get(0) - MARGIN,
                    screen.y() + screen.h() - height.get(0) - MARGIN);
            }
        }
        if (Platform.get() == Platform.WINDOWS) {
            long handle = SDLProperties.SDL_GetPointerProperty(SDLVideo.SDL_GetWindowProperties(window),
                SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER, NO_WINDOW);
            if (handle != NO_WINDOW) {
                User32.SetWindowPos(null, handle, User32.HWND_BOTTOM, 0, 0, 0, 0,
                    User32.SWP_NOMOVE | User32.SWP_NOSIZE | User32.SWP_NOACTIVATE);
            }
        }
    }
}
