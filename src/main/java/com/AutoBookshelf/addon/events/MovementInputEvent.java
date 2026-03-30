package com.AutoBookshelf.addon.events;

import net.minecraft.client.input.Input;

public class MovementInputEvent {
    private static final MovementInputEvent INSTANCE = new MovementInputEvent();

    public Input input;

    public static MovementInputEvent get(Input input) {
        INSTANCE.input = input;
        return INSTANCE;
    }
}