package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;

public final class ChatSendEvent extends Event {

    private final String message;

    public ChatSendEvent(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
