#include <jni.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <pthread.h>

extern int tor_main(int argc, char* argv[]);

static pthread_t tor_thread = 0;
static volatile int tor_running = 0;

void* tor_thread_main(void* arg) {
    char* config_dir = (char*)arg;

    const char* argv[] = {
        "tor",
        "-f", "/data/local/tmp/torrc",
        "--DataDirectory", config_dir,
        NULL
    };

    int argc = 5;
    char* argv_copy[6];
    for (int i = 0; i < argc; i++) {
        argv_copy[i] = (char*)malloc(strlen(argv[i]) + 1);
        strcpy(argv_copy[i], argv[i]);
    }
    argv_copy[argc] = NULL;

    tor_running = 1;
    tor_main(argc, argv_copy);
    tor_running = 0;

    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_lasvegas_secmess_network_TorManager_startTorNative(
        JNIEnv *env, jclass clazz, jstring config_dir_str) {

    const char *config_dir = (*env)->GetStringUTFChars(env, config_dir_str, NULL);

    if (tor_running) {
        return 1; // Already running
    }

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);

    char* config_copy = (char*)malloc(strlen(config_dir) + 1);
    strcpy(config_copy, config_dir);

    int result = pthread_create(&tor_thread, &attr, tor_thread_main, config_copy);
    pthread_attr_destroy(&attr);

    (*env)->ReleaseStringUTFChars(env, config_dir_str, config_dir);

    return result == 0 ? 0 : -1;
}

JNIEXPORT jint JNICALL
Java_com_lasvegas_secmess_network_TorManager_isTorRunning(JNIEnv *env, jclass clazz) {
    return tor_running ? 1 : 0;
}
