package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;

/**
 * Fired when the player submits a chat message. Cancel it to stop the message
 * from reaching the server. This is how client commands work.
 */
public final class ChatSendEvent extends Event {

    private final String message;

    public ChatSendEvent(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
