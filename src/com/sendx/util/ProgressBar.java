package com.sendx.util;

public class ProgressBar {
    private final String label;
    private final long totalBytes;
    private long transferred;
    private long startTime;
    private long lastUpdateTime;
    private long lastUpdateBytes;
    private double smoothSpeed;
    private boolean finished;

    private static final int BAR_WIDTH = 30;
    private static final long UPDATE_INTERVAL_MS = 100;

    public ProgressBar(String label, long totalBytes) {
        this.label = label;
        this.totalBytes = totalBytes;
        this.transferred = 0;
        this.startTime = System.currentTimeMillis();
        this.lastUpdateTime = startTime;
        this.lastUpdateBytes = 0;
        this.smoothSpeed = 0;
        this.finished = false;
    }

    public void update(long bytesTransferred){
        this.transferred = bytesTransferred;
        long now = System.currentTimeMillis();
        long elapsed = now - lastUpdateTime;

        if (elapsed >= UPDATE_INTERVAL_MS || bytesTransferred >= totalBytes){
            long deltaBytes = bytesTransferred - lastUpdateBytes;
            double instantSpeed = elapsed > 0? (deltaBytes * 1000.0 / elapsed) : 0;
            smoothSpeed = smoothSpeed == 0? instantSpeed : smoothSpeed * 0.7 + instantSpeed * 0.3;
            lastUpdateTime = now;
            lastUpdateBytes = bytesTransferred;
            render();
        }
    }

    private void render() {
        double percent = totalBytes > 0? (double) transferred / totalBytes : 0;
        int filled = (int) (percent * BAR_WIDTH);

        StringBuilder bar = new StringBuilder("\r   ");
        bar.append('[');
        for (int i=0; i < BAR_WIDTH; i++){
            bar.append(i < filled ? '\u2588' : '\u2591');
        }
        bar.append("] ");
        bar.append(String.format("%3d%%  ", (int) (percent * 100)));
        bar.append(formatSize(transferred)).append("/").append(formatSize(totalBytes));
        bar.append("    ").append(formatSpeed(smoothSpeed));

        if (smoothSpeed > 0 && transferred < totalBytes) {
            long remaining = totalBytes - transferred;
            long etaSeconds = (long) (remaining / smoothSpeed);
            bar.append("  ETA  ").append(formatTime(etaSeconds));
        }

        bar.append("    ");
        System.out.print(bar);
    }

    public void complete(boolean success) {
        if (finished) return;
        finished = true;
        update(totalBytes);
        long elapsed = System.currentTimeMillis() - startTime;
        System.out.println();
        if (success){
            System.out.println("  " + label + " complete. Time: " + formatTime(elapsed / 1000)
            + ", Avg speed: " + formatSpeed((double) (totalBytes * 1000) / Math.max(elapsed, 1)));
        } else {
            System.out.println("  " + label + " FAILED.");
        }
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
        return String.format("%.2fGB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static String formatSpeed(double bytesPerSec) {
        if (bytesPerSec < 1024) return String.format("%.0fB/s", bytesPerSec);
        if (bytesPerSec < 1024 * 1024) return String.format("%.1fKB/s", bytesPerSec / 1024.0);
        if (bytesPerSec < 1024 * 1024 * 1024) return String.format("%.1fMB/s", bytesPerSec / (1024.0 * 1024.0));
        return String.format("%.2fGB/s", bytesPerSec / (1024.0 * 1024.0 * 1024.0));
    }

    private static String formatTime(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds/ 60) + "m" + (seconds % 60) + "s";
        return (seconds / 3600) + "h" + ((seconds % 3600) / 60) + "m";
    }
}
