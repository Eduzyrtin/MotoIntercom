// Puente JNI con libopus: un codificador y un decodificador por voz, mono. Lo llama opus/Opus.kt.
#include <jni.h>
#include <stdint.h>
#include <opus.h>

#define MAX_FRAME 960   // 20 ms a 48 kHz, lo más que pide la app
#define MAX_PACKET 1500

#define FN(name) Java_com_sanabria_motointercom_opus_OpusNative_##name

JNIEXPORT jlong JNICALL FN(encoderCreate)(JNIEnv *env, jclass clazz, jint sampleRate, jint bitrate) {
    int err = 0;
    OpusEncoder *enc = opus_encoder_create(sampleRate, 1, OPUS_APPLICATION_VOIP, &err);
    if (err != OPUS_OK || enc == NULL) return 0;
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    // Banda completa (20 kHz) en cuanto el bitrate lo permite; con poco bitrate Opus la estrecha solo
    opus_encoder_ctl(enc, OPUS_SET_MAX_BANDWIDTH(OPUS_BANDWIDTH_FULLBAND));
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(10));
    // Redundancia de la trama anterior dentro de cada paquete: si un enlace descarta una, la siguiente la reconstruye
    opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(enc, OPUS_SET_PACKET_LOSS_PERC(10));
    opus_encoder_ctl(enc, OPUS_SET_DTX(0));
    return (jlong) (intptr_t) enc;
}

JNIEXPORT void JNICALL FN(encoderSetBitrate)(JNIEnv *env, jclass clazz, jlong handle, jint bitrate) {
    if (handle) opus_encoder_ctl((OpusEncoder *) (intptr_t) handle, OPUS_SET_BITRATE(bitrate));
}

JNIEXPORT jint JNICALL FN(encode)(JNIEnv *env, jclass clazz, jlong handle, jshortArray pcm, jint frameSize, jbyteArray out) {
    if (!handle || frameSize <= 0 || frameSize > MAX_FRAME) return OPUS_BAD_ARG;
    opus_int16 in[MAX_FRAME];
    unsigned char buf[MAX_PACKET];
    (*env)->GetShortArrayRegion(env, pcm, 0, frameSize, in);
    jsize cap = (*env)->GetArrayLength(env, out);
    if (cap > MAX_PACKET) cap = MAX_PACKET;
    int n = opus_encode((OpusEncoder *) (intptr_t) handle, in, frameSize, buf, cap);
    if (n > 0) (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte *) buf);
    return n;
}

JNIEXPORT void JNICALL FN(encoderDestroy)(JNIEnv *env, jclass clazz, jlong handle) {
    if (handle) opus_encoder_destroy((OpusEncoder *) (intptr_t) handle);
}

JNIEXPORT jlong JNICALL FN(decoderCreate)(JNIEnv *env, jclass clazz, jint sampleRate) {
    int err = 0;
    OpusDecoder *dec = opus_decoder_create(sampleRate, 1, &err);
    if (err != OPUS_OK || dec == NULL) return 0;
    return (jlong) (intptr_t) dec;
}

// packet == NULL o len == 0: ocultación de pérdida. fec: reconstruir la trama anterior a [packet] con su redundancia.
JNIEXPORT jint JNICALL FN(decode)(JNIEnv *env, jclass clazz, jlong handle, jbyteArray packet, jint len, jshortArray pcm,
                                  jint frameSize, jboolean fec) {
    if (!handle || frameSize <= 0 || frameSize > MAX_FRAME) return OPUS_BAD_ARG;
    opus_int16 out[MAX_FRAME];
    unsigned char buf[MAX_PACKET];
    const unsigned char *data = NULL;
    if (packet != NULL && len > 0) {
        if (len > MAX_PACKET) return OPUS_BAD_ARG;
        (*env)->GetByteArrayRegion(env, packet, 0, len, (jbyte *) buf);
        data = buf;
    } else {
        len = 0;
    }
    int n = opus_decode((OpusDecoder *) (intptr_t) handle, data, len, out, frameSize, fec ? 1 : 0);
    if (n > 0) (*env)->SetShortArrayRegion(env, pcm, 0, n, out);
    return n;
}

JNIEXPORT void JNICALL FN(decoderDestroy)(JNIEnv *env, jclass clazz, jlong handle) {
    if (handle) opus_decoder_destroy((OpusDecoder *) (intptr_t) handle);
}
