// Запуск byedpi из приложения: тот же ciadpi, только main() вызывается из Kotlin (ByeDpi.run) с аргументами
// стратегии и работает, пока его не остановят (ByeDpi.stop). Между запусками возвращаем настройки к исходным:
// byedpi рассчитан на один запуск на процесс.

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <getopt.h>
#include <sys/socket.h>

#include "params.h"

extern int server_fd;
int ciadpi_main(int argc, char **argv);

static struct params defaults;
static int saved;

JNIEXPORT jint JNICALL
Java_com_fiolkid_app_engine_ByeDpi_run(JNIEnv *env, jobject thiz, jobjectArray args)
{
    if (!saved) {
        defaults = params;
        saved = 1;
    } else {
        params = defaults;
    }

    int count = (*env)->GetArrayLength(env, args);
    char **argv = calloc(count + 2, sizeof(char *));
    if (!argv)
        return -1;
    argv[0] = strdup("ciadpi");
    for (int i = 0; i < count; i++) {
        jstring arg = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        const char *chars = (*env)->GetStringUTFChars(env, arg, 0);
        argv[i + 1] = strdup(chars);
        (*env)->ReleaseStringUTFChars(env, arg, chars);
        (*env)->DeleteLocalRef(env, arg);
    }

    // optind = 0 — полный сброс разбора аргументов перед новым запуском
    optind = 0;
    server_fd = -1;
    int status = ciadpi_main(count + 1, argv);

    for (int i = 0; i < count + 1; i++)
        free(argv[i]);
    free(argv);
    return status;
}

JNIEXPORT void JNICALL
Java_com_fiolkid_app_engine_ByeDpi_stop(JNIEnv *env, jobject thiz)
{
    if (server_fd > 0)
        shutdown(server_fd, SHUT_RDWR);
}
