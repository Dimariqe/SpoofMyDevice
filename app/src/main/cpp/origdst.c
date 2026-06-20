#include <jni.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <linux/netfilter_ipv4.h>
#include <string.h>
#include <errno.h>

JNIEXPORT jint JNICALL
Java_com_devicespooflab_hooks_proxy_OrigDst_nativeReadDst(
        JNIEnv *env, jclass cls, jint fd, jbyteArray out) {
    struct sockaddr_in addr;
    socklen_t len = sizeof(addr);
    memset(&addr, 0, len);
    if (getsockopt(fd, SOL_IP, SO_ORIGINAL_DST, &addr, &len) != 0) {
        return -errno;
    }
    jbyte buf[6];
    memcpy(buf, &addr.sin_addr.s_addr, 4);
    memcpy(buf + 4, &addr.sin_port, 2);
    (*env)->SetByteArrayRegion(env, out, 0, 6, buf);
    return 0;
}
