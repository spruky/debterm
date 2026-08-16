// Minimal PTY bridge. Android has no Java API for openpty(), so this is the
// only native code in the app: allocate a master/slave pair, fork, make the
// slave the child's controlling terminal, exec. Everything else is Kotlin.
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <termios.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/wait.h>

static char **to_vec(JNIEnv *e, jobjectArray a) {
    jsize n = a ? (*e)->GetArrayLength(e, a) : 0;
    char **v = (char **) calloc((size_t) n + 1, sizeof(char *));
    jsize i;
    for (i = 0; i < n; i++) {
        jstring s = (jstring) (*e)->GetObjectArrayElement(e, a, i);
        const char *c = (*e)->GetStringUTFChars(e, s, 0);
        v[i] = strdup(c);
        (*e)->ReleaseStringUTFChars(e, s, c);
        (*e)->DeleteLocalRef(e, s);
    }
    return v;
}

static void free_vec(char **v) {
    char **p;
    if (!v) return;
    for (p = v; *p; p++) free(*p);
    free(v);
}

JNIEXPORT jint JNICALL
Java_io_spruky_debterm_Pty_start(JNIEnv *e, jobject self, jstring jcmd, jobjectArray jargv,
                                 jobjectArray jenv, jintArray jpid, jint rows, jint cols) {
    const char *tmp = (*e)->GetStringUTFChars(e, jcmd, 0);
    char *cmd = strdup(tmp);
    (*e)->ReleaseStringUTFChars(e, jcmd, tmp);
    char **argv = to_vec(e, jargv);
    char **envp = to_vec(e, jenv);
    char pts[128];
    struct winsize ws;
    pid_t pid;
    jint out;

    int m = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (m < 0) goto bail;
    if (grantpt(m) || unlockpt(m) || ptsname_r(m, pts, sizeof(pts))) goto bail_fd;

    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ioctl(m, TIOCSWINSZ, &ws);

    pid = fork();
    if (pid < 0) goto bail_fd;
    if (pid == 0) {
        int s;
        close(m);
        setsid();
        s = open(pts, O_RDWR);
        if (s < 0) _exit(1);
        ioctl(s, TIOCSCTTY, 0);
        dup2(s, 0);
        dup2(s, 1);
        dup2(s, 2);
        if (s > 2) close(s);
        signal(SIGCHLD, SIG_DFL);
        signal(SIGPIPE, SIG_DFL);
        signal(SIGTTOU, SIG_IGN);
        execve(cmd, argv, envp);
        _exit(127);
    }

    out = (jint) pid;
    (*e)->SetIntArrayRegion(e, jpid, 0, 1, &out);
    free(cmd);
    free_vec(argv);
    free_vec(envp);
    return m;

bail_fd:
    close(m);
bail:
    free(cmd);
    free_vec(argv);
    free_vec(envp);
    return -1;
}

JNIEXPORT void JNICALL
Java_io_spruky_debterm_Pty_setSize(JNIEnv *e, jobject self, jint fd, jint rows, jint cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_io_spruky_debterm_Pty_waitFor(JNIEnv *e, jobject self, jint pid) {
    int st = 0;
    while (waitpid((pid_t) pid, &st, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    if (WIFEXITED(st)) return WEXITSTATUS(st);
    if (WIFSIGNALED(st)) return 128 + WTERMSIG(st);
    return -1;
}

JNIEXPORT void JNICALL
Java_io_spruky_debterm_Pty_killPg(JNIEnv *e, jobject self, jint pid, jint sig) {
    kill((pid_t) -pid, sig);
    kill((pid_t) pid, sig);
}
