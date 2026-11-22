/*
 * Oralis — JNI bridge to the LAME MP3 encoder.
 *
 * The Kotlin side (app.oralis.audio.mp3.LameEncoder) streams 16-bit mono PCM
 * in small blocks, so memory use is constant regardless of audio length.
 * After the last block, flush() emits the remaining frames and
 * lametag() returns the LAME/Xing "Info" frame that must overwrite the
 * placeholder frame written at the start of the stream (gives players an
 * exact duration and gapless information).
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "lame.h"

#define ORALIS_ERR_ARGS (-100)
#define ORALIS_ERR_INIT (-101)

static lame_global_flags *as_gf(jlong handle) {
    return (lame_global_flags *) (intptr_t) handle;
}

JNIEXPORT jlong JNICALL
Java_app_oralis_audio_mp3_LameNative_nativeInit(JNIEnv *env, jclass clazz,
                                                jint sampleRate, jint bitrateKbps, jint quality) {
    (void) env;
    (void) clazz;
    lame_global_flags *gf = lame_init();
    if (gf == NULL) return 0;
    lame_set_num_channels(gf, 1);
    lame_set_mode(gf, MONO);
    lame_set_in_samplerate(gf, sampleRate);
    lame_set_out_samplerate(gf, sampleRate);
    lame_set_VBR(gf, vbr_off);
    lame_set_brate(gf, bitrateKbps);
    lame_set_quality(gf, quality);
    /* Writes a placeholder Xing/Info frame first; replaced by lametag(). */
    lame_set_bWriteVbrTag(gf, 1);
    lame_set_write_id3tag_automatic(gf, 0);
    if (lame_init_params(gf) < 0) {
        lame_close(gf);
        return 0;
    }
    return (jlong) (intptr_t) gf;
}

JNIEXPORT jint JNICALL
Java_app_oralis_audio_mp3_LameNative_nativeEncode(JNIEnv *env, jclass clazz, jlong handle,
                                                  jshortArray pcm, jint count, jbyteArray out) {
    (void) clazz;
    lame_global_flags *gf = as_gf(handle);
    if (gf == NULL || pcm == NULL || out == NULL || count < 0) return ORALIS_ERR_ARGS;
    jsize pcmLen = (*env)->GetArrayLength(env, pcm);
    if (count > pcmLen) return ORALIS_ERR_ARGS;
    jsize outLen = (*env)->GetArrayLength(env, out);
    /* LAME worst case: 1.25 * samples + 7200 bytes. */
    if ((double) outLen < 1.25 * (double) count + 7200.0) return ORALIS_ERR_ARGS;

    jshort *samples = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *bytes = (*env)->GetByteArrayElements(env, out, NULL);
    if (samples == NULL || bytes == NULL) {
        if (samples) (*env)->ReleaseShortArrayElements(env, pcm, samples, JNI_ABORT);
        if (bytes) (*env)->ReleaseByteArrayElements(env, out, bytes, JNI_ABORT);
        return ORALIS_ERR_ARGS;
    }
    int written = lame_encode_buffer(gf, samples, samples, count,
                                     (unsigned char *) bytes, outLen);
    (*env)->ReleaseShortArrayElements(env, pcm, samples, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, bytes, 0);
    return written;
}

JNIEXPORT jint JNICALL
Java_app_oralis_audio_mp3_LameNative_nativeFlush(JNIEnv *env, jclass clazz, jlong handle,
                                                 jbyteArray out) {
    (void) clazz;
    lame_global_flags *gf = as_gf(handle);
    if (gf == NULL || out == NULL) return ORALIS_ERR_ARGS;
    jsize outLen = (*env)->GetArrayLength(env, out);
    if (outLen < 7200) return ORALIS_ERR_ARGS;
    jbyte *bytes = (*env)->GetByteArrayElements(env, out, NULL);
    if (bytes == NULL) return ORALIS_ERR_ARGS;
    int written = lame_encode_flush(gf, (unsigned char *) bytes, outLen);
    (*env)->ReleaseByteArrayElements(env, out, bytes, 0);
    return written;
}

JNIEXPORT jint JNICALL
Java_app_oralis_audio_mp3_LameNative_nativeLameTag(JNIEnv *env, jclass clazz, jlong handle,
                                                   jbyteArray out) {
    (void) clazz;
    lame_global_flags *gf = as_gf(handle);
    if (gf == NULL || out == NULL) return ORALIS_ERR_ARGS;
    jsize outLen = (*env)->GetArrayLength(env, out);
    jbyte *bytes = (*env)->GetByteArrayElements(env, out, NULL);
    if (bytes == NULL) return ORALIS_ERR_ARGS;
    size_t size = lame_get_lametag_frame(gf, (unsigned char *) bytes, (size_t) outLen);
    (*env)->ReleaseByteArrayElements(env, out, bytes, 0);
    if (size > (size_t) outLen) return ORALIS_ERR_ARGS; /* buffer too small */
    return (jint) size;
}

JNIEXPORT jint JNICALL
Java_app_oralis_audio_mp3_LameNative_nativeEncoderDelay(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    lame_global_flags *gf = as_gf(handle);
    if (gf == NULL) return ORALIS_ERR_ARGS;
    return lame_get_encoder_delay(gf);
}

JNIEXPORT void JNICALL
Java_app_oralis_audio_mp3_LameNative_nativeClose(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    lame_global_flags *gf = as_gf(handle);
    if (gf != NULL) lame_close(gf);
}

JNIEXPORT jstring JNICALL
Java_app_oralis_audio_mp3_LameNative_nativeVersion(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, get_lame_version());
}
