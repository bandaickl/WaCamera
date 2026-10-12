package dev.wacamera.client;

/** Temporary renderer state is restored before the player's normal world pass. */
public interface CaptureLevelRenderer {
    void wacamera$beginCapture();
    void wacamera$endCapture();
}
