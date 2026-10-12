package dev.wacamera.client;

/** Temporary renderer state is always restored before the player's normal world pass. */
public interface CaptureLevelRenderer {
    void wacamera$beginCapture();
    void wacamera$endCapture();
}
