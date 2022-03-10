#include <jni.h>
#include <string>
#include <libyuv.h>
#include "libyuv.h"

extern "C" {

JNIEXPORT
JNICALL
jint Java_com_microblink_flutter_MicroblinkFlutterPlugin_J420ToARGB(
        JNIEnv *env, jclass jcls,
        jbyteArray src_y, jint src_stride_y,
        jbyteArray src_u, jint src_stride_u,
        jbyteArray src_v, jint src_stride_v,
        jbyteArray dst_argb, jint dst_stride_argb,
        jint width, jint height
) {

    uint8_t *srcY = (uint8_t *) env->GetByteArrayElements(src_y, 0);
    uint8_t *srcU = (uint8_t *) env->GetByteArrayElements(src_u, 0);
    uint8_t *srcV = (uint8_t *) env->GetByteArrayElements(src_v, 0);
    uint8_t *dstARGB = (uint8_t *) env->GetByteArrayElements(dst_argb, 0);

    jint result = libyuv::J420ToARGB(
            srcY, src_stride_y,
            srcU, src_stride_u,
            srcV, src_stride_v,
            dstARGB, dst_stride_argb,
            width, height);

    //remember release
    env->ReleaseByteArrayElements(src_y, (jbyte *) srcY, 0);
    env->ReleaseByteArrayElements(src_u, (jbyte *) srcU, 0);
    env->ReleaseByteArrayElements(src_v, (jbyte *) srcV, 0);
    env->ReleaseByteArrayElements(dst_argb, (jbyte *) dstARGB, 0);

    return result;
}
}
