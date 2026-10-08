#include <jni.h>
#include <node.h>
#include <sys/socket.h>
#include <unistd.h>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

static std::atomic<bool> started{false};
static std::string utf8(JNIEnv* env, jstring value) {
    // Java passes only native-provisioned filesystem paths. Use UTF-8 bytes
    // via String.getBytes rather than JNI modified UTF-8 for non-ASCII paths.
    jclass string_class = env->FindClass("java/lang/String");
    jmethodID method = env->GetMethodID(string_class, "getBytes", "(Ljava/lang/String;)[B");
    jstring encoding = env->NewStringUTF("UTF-8");
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, method, encoding));
    const jsize size = env->GetArrayLength(bytes);
    std::string result(static_cast<size_t>(size), '\0');
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(result.data()));
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(encoding); env->DeleteLocalRef(string_class);
    return result;
}
extern "C" JNIEXPORT jintArray JNICALL
Java_dev_mrsurge_electromux_node_NodeNative_socketPair(JNIEnv* env, jobject) {
    int descriptors[2];
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, descriptors) != 0) return nullptr;
    jintArray result = env->NewIntArray(2);
    if (!result) { close(descriptors[0]); close(descriptors[1]); return nullptr; }
    const jint values[2] = {descriptors[0], descriptors[1]};
    env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}
extern "C" JNIEXPORT void JNICALL
Java_dev_mrsurge_electromux_node_NodeNative_shutdownSocket(JNIEnv*, jobject, jint fd) {
    shutdown(fd, SHUT_RDWR);
}
extern "C" JNIEXPORT jint JNICALL
Java_dev_mrsurge_electromux_node_NodeNative_start(JNIEnv* env, jobject, jstring entry,
                                                jstring home, jstring temp, jint fd, jboolean termux) {
    if (started.exchange(true)) { close(fd); return -1; }
    const auto entry_path = utf8(env, entry), home_path = utf8(env, home), temp_path = utf8(env, temp);
    if (setenv("HOME", home_path.c_str(), 1) || setenv("TMPDIR", temp_path.c_str(), 1) ||
        chdir(home_path.c_str())) { close(fd); return -2; }
    // Never authorize options inherited from unrelated host/Termux processes.
    unsetenv("NODE_OPTIONS"); unsetenv("NODE_PATH"); unsetenv("NODE_ICU_DATA");
    std::vector<std::string> values{"electromux-node", entry_path, std::to_string(fd), home_path,
        termux ? "termux" : "standalone"};
    size_t size = 0; for (const auto& value : values) size += value.size() + 1;
    std::vector<char> storage(size); std::vector<char*> argv;
    char* at = storage.data();
    for (const auto& value : values) {
        argv.push_back(at); memcpy(at, value.c_str(), value.size() + 1); at += value.size() + 1;
    }
    argv.push_back(nullptr);
    // Node owns its socket endpoint once the main JS net.Socket adopts it.
    // Do not close that numerical FD after Node returns (it may have been reused).
    return node::Start(static_cast<int>(values.size()), argv.data());
}
