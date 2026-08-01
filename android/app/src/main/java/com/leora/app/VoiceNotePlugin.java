package com.leora.app;

import android.content.Intent;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Bridges JS calls (see components/PushNotificationBridge.tsx in the main
 * Leora repo — the web app loaded remotely into this WebView) to
 * VoiceNotePlaybackService, which owns the real MediaPlayer + MediaSession +
 * MediaStyle notification. Registered manually in MainActivity since this is
 * an app-local plugin, not a separate Capacitor plugin package.
 */
@CapacitorPlugin(name = "VoiceNotePlayer")
public class VoiceNotePlugin extends Plugin {

    @PluginMethod
    public void play(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("url is required");
            return;
        }
        String title = call.getString("title", "Leora");
        String body = call.getString("body", "Voice note");

        Intent intent = new Intent(getContext(), VoiceNotePlaybackService.class);
        intent.setAction(VoiceNotePlaybackService.ACTION_PLAY);
        intent.putExtra(VoiceNotePlaybackService.EXTRA_URL, url);
        intent.putExtra(VoiceNotePlaybackService.EXTRA_TITLE, title);
        intent.putExtra(VoiceNotePlaybackService.EXTRA_BODY, body);
        getContext().startForegroundService(intent);

        call.resolve();
    }

    @PluginMethod
    public void pause(PluginCall call) {
        sendAction(VoiceNotePlaybackService.ACTION_PAUSE);
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        sendAction(VoiceNotePlaybackService.ACTION_STOP);
        call.resolve();
    }

    private void sendAction(String action) {
        Intent intent = new Intent(getContext(), VoiceNotePlaybackService.class);
        intent.setAction(action);
        getContext().startService(intent);
    }
}
