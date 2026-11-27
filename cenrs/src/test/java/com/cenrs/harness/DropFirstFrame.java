package com.cenrs.harness;

public final class DropFirstFrame {
    private final String targetFrame;
    private boolean alreadyDropped = false;

    public DropFirstFrame(String targetFrame) {
        this.targetFrame = targetFrame;
    }

    public synchronized boolean shouldDrop(String frame) {
        if (!alreadyDropped && targetFrame.equals(frame)) {
            alreadyDropped = true;
            return true;
        }
        return false;
    }
}