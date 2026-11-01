// Thin JNI bridge to FluidSynth: one synthesizer for the whole app.
// Channels 0-15 belong to the song player; channel 16 is the on-screen / echoed keyboard.
#include <jni.h>
#include <android/log.h>
#include <fluidsynth.h>

#define TAG "MixokeysSynth"
#define FN(name) Java_com_sirveillanceai_mixokeys_audio_Synth_##name

static fluid_settings_t *settings = NULL;
static fluid_synth_t *synth = NULL;
static fluid_audio_driver_t *adriver = NULL;
static fluid_player_t *player = NULL;

static void song_sounds_off(void) {
    if (!synth) return;
    for (int ch = 0; ch < 16; ch++) fluid_synth_all_sounds_off(synth, ch);
}

JNIEXPORT jboolean JNICALL FN(nInit)(JNIEnv *env, jclass cls, jstring sfPath, jint periodSize) {
    if (synth) return JNI_TRUE;
    settings = new_fluid_settings();
    fluid_settings_setstr(settings, "audio.driver", "oboe");
    fluid_settings_setstr(settings, "audio.oboe.performance-mode", "LowLatency");
    fluid_settings_setstr(settings, "audio.oboe.sharing-mode", "Shared");
    fluid_settings_setint(settings, "audio.period-size", periodSize);
    fluid_settings_setint(settings, "audio.periods", 2);
    fluid_settings_setnum(settings, "synth.sample-rate", 48000.0);
    fluid_settings_setint(settings, "synth.midi-channels", 32);
    fluid_settings_setint(settings, "synth.polyphony", 192);
    fluid_settings_setnum(settings, "synth.gain", 0.55);
    fluid_settings_setint(settings, "synth.reverb.active", 1);
    fluid_settings_setint(settings, "synth.chorus.active", 0);
    synth = new_fluid_synth(settings);
    if (!synth) { __android_log_print(ANDROID_LOG_ERROR, TAG, "new_fluid_synth failed"); return JNI_FALSE; }
    const char *path = (*env)->GetStringUTFChars(env, sfPath, 0);
    int id = fluid_synth_sfload(synth, path, 1);
    (*env)->ReleaseStringUTFChars(env, sfPath, path);
    if (id == FLUID_FAILED) { __android_log_print(ANDROID_LOG_ERROR, TAG, "soundfont failed to load"); return JNI_FALSE; }
    adriver = new_fluid_audio_driver(settings, synth);
    if (!adriver) {
        // fall back to OpenSL ES if Oboe is unavailable
        fluid_settings_setstr(settings, "audio.driver", "opensles");
        adriver = new_fluid_audio_driver(settings, synth);
    }
    if (!adriver) { __android_log_print(ANDROID_LOG_ERROR, TAG, "no audio driver"); return JNI_FALSE; }
    fluid_synth_program_change(synth, 16, 0);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL FN(nNoteOn)(JNIEnv *env, jclass cls, jint ch, jint key, jint vel) { if (synth) fluid_synth_noteon(synth, ch, key, vel); }
JNIEXPORT void JNICALL FN(nNoteOff)(JNIEnv *env, jclass cls, jint ch, jint key) { if (synth) fluid_synth_noteoff(synth, ch, key); }
JNIEXPORT void JNICALL FN(nProgram)(JNIEnv *env, jclass cls, jint ch, jint prog) { if (synth) fluid_synth_program_change(synth, ch, prog); }

JNIEXPORT jboolean JNICALL FN(nLoad)(JNIEnv *env, jclass cls, jstring midiPath) {
    if (!synth) return JNI_FALSE;
    if (player) { fluid_player_stop(player); delete_fluid_player(player); player = NULL; }
    song_sounds_off();
    player = new_fluid_player(synth);
    if (!player) return JNI_FALSE;
    const char *path = (*env)->GetStringUTFChars(env, midiPath, 0);
    int ok = fluid_player_add(player, path);
    (*env)->ReleaseStringUTFChars(env, midiPath, path);
    return ok == FLUID_OK ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL FN(nPlay)(JNIEnv *env, jclass cls) { if (player) fluid_player_play(player); }
JNIEXPORT void JNICALL FN(nStop)(JNIEnv *env, jclass cls) { if (player) fluid_player_stop(player); song_sounds_off(); }
JNIEXPORT jboolean JNICALL FN(nSeek)(JNIEnv *env, jclass cls, jint tick) {
    if (!player) return JNI_FALSE;
    song_sounds_off();
    return fluid_player_seek(player, tick) == FLUID_OK ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jint JNICALL FN(nTick)(JNIEnv *env, jclass cls) { return player ? fluid_player_get_current_tick(player) : 0; }
JNIEXPORT jint JNICALL FN(nTotalTicks)(JNIEnv *env, jclass cls) { return player ? fluid_player_get_total_ticks(player) : 0; }
JNIEXPORT jint JNICALL FN(nStatus)(JNIEnv *env, jclass cls) { return player ? fluid_player_get_status(player) : FLUID_PLAYER_DONE; }
JNIEXPORT void JNICALL FN(nTempo)(JNIEnv *env, jclass cls, jdouble mult) { if (player) fluid_player_set_tempo(player, FLUID_PLAYER_TEMPO_INTERNAL, mult); }
